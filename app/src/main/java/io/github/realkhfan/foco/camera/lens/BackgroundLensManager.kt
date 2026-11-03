package io.github.realkhfan.foco.camera.lens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import io.github.realkhfan.foco.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

private const val TAG = "BackgroundLensManager"

/**
 * Manages background secondary lens preparation for instant lens switching.
 * Replicated from source reference implementation:
 * - Keeps secondary physical lens prepared in background when hardware supports it.
 * - Detects simultaneous camera limitations (concurrent camera IDs, logical multi-camera physical streams).
 * - Avoids unnecessary camera reopening/initialization during switching.
 * - Prevents preview freezing, black frames, or resource leaks.
 * - Can be toggled on/off in settings.
 */
class BackgroundLensManager(
    private val context: Context,
    private val settings: AppSettings,
    private val cameraManager: CameraManager?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundHandler: Handler? = null

    private val _status = MutableStateFlow(BackgroundCameraStatus.OFF)
    val status: StateFlow<BackgroundCameraStatus> = _status.asStateFlow()

    @Volatile
    private var standbyCameraDevice: CameraDevice? = null
    @Volatile
    private var standbyCaptureSession: CameraCaptureSession? = null
    @Volatile
    private var standbySurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var standbySurface: Surface? = null
    @Volatile
    private var standbyLens: LensInfo? = null

    private val isPreparingStandby = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private val standbySessionGen = AtomicInteger(0)
    private val standbyDeviceGen = AtomicInteger(0)

    fun setBackgroundHandler(handler: Handler) {
        backgroundHandler = handler
    }

    fun isStandbyReady(): Boolean {
        return standbyCameraDevice != null && standbyCaptureSession != null && isStreaming.get()
    }

    fun getStandbyLens(): LensInfo? = standbyLens

    fun detectSimultaneousStreamingMode(mainLens: LensInfo?, secondaryLens: LensInfo?): DualStreamingMode {
        if (mainLens == null || secondaryLens == null) return DualStreamingMode.NONE
        val mgr = cameraManager ?: return DualStreamingMode.NONE

        if (mainLens.cameraId == secondaryLens.cameraId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val chars = try { mgr.getCameraCharacteristics(mainLens.cameraId) } catch (_: Throwable) { null }
                val physIds = chars?.physicalCameraIds ?: emptySet()
                val targetPhysId = secondaryLens.physicalCameraId
                if (targetPhysId != null && physIds.contains(targetPhysId) && secondaryLens.supportsPhysicalStream) {
                    return DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                }
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrentSets = mgr.concurrentCameraIds
                    if (concurrentSets.isNotEmpty()) {
                        val matches = concurrentSets.any { combo ->
                            combo.contains(mainLens.cameraId) && combo.contains(secondaryLens.cameraId)
                        }
                        if (matches) return DualStreamingMode.CONCURRENT_DEVICES
                    }
                } catch (ignored: Throwable) {}
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        }
    }

    @SuppressLint("MissingPermission")
    fun prepareSecondaryLens(mainLens: LensInfo?, secondaryLens: LensInfo?, targetSize: Size = Size(1920, 1080)) {
        if (!settings.backgroundLensPreparation) {
            release()
            return
        }
        if (secondaryLens == null || cameraManager == null) return

        val dualMode = detectSimultaneousStreamingMode(mainLens, secondaryLens)
        Log.i(TAG, "Preparing secondary lens ${secondaryLens.displayName} via dualMode=$dualMode")

        if (dualMode == DualStreamingMode.NONE) {
            _status.value = BackgroundCameraStatus.UNAVAILABLE
            return
        }

        // If logical multi-camera with physical stream, the active logical device already has access
        if (dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL && mainLens?.cameraId == secondaryLens.cameraId) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            isStreaming.set(true)
            standbyLens = secondaryLens
            return
        }

        // Separate CameraDevices
        val existingCam = standbyCameraDevice
        if (existingCam != null && existingCam.id == secondaryLens.cameraId && standbyCaptureSession != null) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            isStreaming.set(true)
            return
        }

        if (!isPreparingStandby.compareAndSet(false, true)) {
            return
        }

        _status.value = BackgroundCameraStatus.PREPARING
        val openToken = standbyDeviceGen.incrementAndGet()

        try {
            val texture = standbySurfaceTexture ?: SurfaceTexture(0).also {
                it.setDefaultBufferSize(max(targetSize.width, targetSize.height), min(targetSize.width, targetSize.height))
                standbySurfaceTexture = it
            }
            if (standbySurface == null || !standbySurface!!.isValid) {
                standbySurface?.release()
                standbySurface = Surface(texture)
            }

            cameraManager.openCamera(secondaryLens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openToken != standbyDeviceGen.get() || !settings.backgroundLensPreparation) {
                        isPreparingStandby.set(false)
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    standbyCameraDevice = camera
                    standbyLens = secondaryLens
                    configureStandbySession(camera, secondaryLens)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (standbyCameraDevice == camera) {
                        release()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Standby camera ${camera.id} error: $error")
                    if (standbyCameraDevice == camera || standbyCameraDevice == null) {
                        release()
                        _status.value = BackgroundCameraStatus.UNAVAILABLE
                    }
                }
            }, backgroundHandler ?: mainHandler)
        } catch (t: Throwable) {
            isPreparingStandby.set(false)
            Log.e(TAG, "Failed opening standby camera ${secondaryLens.cameraId}", t)
            _status.value = BackgroundCameraStatus.UNAVAILABLE
        }
    }

    private fun configureStandbySession(camera: CameraDevice, lens: LensInfo) {
        val surf = standbySurface ?: run {
            isPreparingStandby.set(false)
            return
        }
        val configToken = standbySessionGen.incrementAndGet()

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (configToken != standbySessionGen.get() || !settings.backgroundLensPreparation) {
                        isPreparingStandby.set(false)
                        try { session.close() } catch (_: Throwable) {}
                        return
                    }
                    standbyCaptureSession = session
                    isPreparingStandby.set(false)
                    isStreaming.set(true)
                    _status.value = BackgroundCameraStatus.READY_QUIET

                    try {
                        session.setRepeatingRequest(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                if (_status.value != BackgroundCameraStatus.READY_QUIET) {
                                    _status.value = BackgroundCameraStatus.READY_QUIET
                                }
                            }
                        }, backgroundHandler ?: mainHandler)
                        Log.i(TAG, "Standby session configured on camera ID ${camera.id} (${lens.displayName})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed repeating request on standby camera ${camera.id}", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    isPreparingStandby.set(false)
                    _status.value = BackgroundCameraStatus.UNAVAILABLE
                    Log.w(TAG, "Standby capture session configuration failed on ${camera.id}")
                }

                override fun onClosed(session: CameraCaptureSession) {
                    if (standbyCaptureSession == session) {
                        standbyCaptureSession = null
                        isStreaming.set(false)
                        _status.value = BackgroundCameraStatus.OFF
                    }
                }
            }, backgroundHandler ?: mainHandler)
        } catch (e: Exception) {
            isPreparingStandby.set(false)
            Log.e(TAG, "Failed configuring standby session on camera ${camera.id}", e)
            _status.value = BackgroundCameraStatus.UNAVAILABLE
        }
    }

    fun release() {
        standbyDeviceGen.incrementAndGet()
        standbySessionGen.incrementAndGet()
        isPreparingStandby.set(false)
        isStreaming.set(false)
        _status.value = BackgroundCameraStatus.OFF

        try { standbyCaptureSession?.stopRepeating() } catch (_: Throwable) {}
        try { standbyCaptureSession?.close() } catch (_: Throwable) {}
        standbyCaptureSession = null

        try { standbyCameraDevice?.close() } catch (_: Throwable) {}
        standbyCameraDevice = null

        try { standbySurface?.release() } catch (_: Throwable) {}
        standbySurface = null

        try { standbySurfaceTexture?.release() } catch (_: Throwable) {}
        standbySurfaceTexture = null

        standbyLens = null
    }
}
