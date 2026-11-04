package io.github.realkhfan.foco.camera.lens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
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
    private var standbySurface: Surface? = null
    @Volatile
    private var ownsStandbySurface = false
    @Volatile
    private var standbyLens: LensInfo? = null
    @Volatile
    private var mainLens: LensInfo? = null
    @Volatile
    private var latestMainRequest: CaptureRequest? = null
    @Volatile
    private var latestMainResult: TotalCaptureResult? = null
    @Volatile
    private var currentUiZoom = 1.0f
    @Volatile
    private var isUltraWideActive = false
    @Volatile
    private var isFrameHandoffActive = false
    @Volatile
    private var lastMainSyncAtMs = 0L

    private val isPreparingStandby = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private val standbySessionGen = AtomicInteger(0)
    private val standbyDeviceGen = AtomicInteger(0)

    var onFrameAvailable: ((PreviewStreamSource) -> Unit)? = null

    fun setBackgroundHandler(handler: Handler) {
        backgroundHandler = handler
    }

    /** Use the actual Ultra-Wide TextureView surface; this Surface is owned by CameraEngine. */
    fun setPreviewSurface(surface: Surface?) {
        if (standbySurface === surface) return
        val sessionWasActive = standbyCaptureSession != null || standbyCameraDevice != null
        if (sessionWasActive) release()
        if (ownsStandbySurface) runCatching { standbySurface?.release() }
        standbySurface = surface?.takeIf { it.isValid }
        ownsStandbySurface = false
        if (surface == null) {
            _status.value = BackgroundCameraStatus.OFF
        }
    }

    /** Called by the actual UW TextureView buffer callback, never by a CaptureResult callback. */
    fun onPreviewFrameAvailable(source: PreviewStreamSource) {
        if (source != PreviewStreamSource.ULTRAWIDE || standbyCaptureSession == null) return
        val firstFrame = !isStreaming.getAndSet(true)
        if (firstFrame || _status.value != BackgroundCameraStatus.READY_QUIET) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            Log.i(TAG, "[STANDBY_READY] cameraId=${standbyCameraDevice?.id} lens=${standbyLens?.displayName} firstTextureFrame=true")
        }
    }

    fun syncMainCaptureResult(request: CaptureRequest, result: TotalCaptureResult) {
        latestMainRequest = request
        latestMainResult = result
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMainSyncAtMs < 120L) return
        lastMainSyncAtMs = now
        syncStandbyStreamSettings()
    }

    /** Mirror UI zoom during active UW use or frame handoff; otherwise park UW at the switch-point FOV. */
    fun updateZoom(uiZoom: Float, handoffActive: Boolean, ultraWideIsActive: Boolean) {
        currentUiZoom = uiZoom.coerceAtLeast(0.5f)
        isFrameHandoffActive = handoffActive
        isUltraWideActive = ultraWideIsActive
        syncStandbyStreamSettings()
    }

    fun syncStandbyStreamSettings() {
        if (!settings.backgroundLensPreparation || standbyCaptureSession == null) return
        val session = standbyCaptureSession ?: return
        val camera = standbyCameraDevice ?: return
        val lens = standbyLens ?: return
        val builder = standbyRequestBuilder ?: return
        try {
            applySynchronizedStandbySettings(builder, lens)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler ?: mainHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to synchronize standby repeating request", t)
        }
    }

    private fun calculateStandbyCropRatio(lens: LensInfo): Float {
        val mainEq = mainLens?.equivalent35mmFocalMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        val uwEq = lens.equivalent35mmFocalMm
            .takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        val zoomOverride = if (isFrameHandoffActive || isUltraWideActive) currentUiZoom else null
        return if (zoomOverride != null) {
            CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = zoomOverride,
                lensBaseRatio = lens.baseZoomRatio,
                lensType = lens.lensType,
                uwEquivalentFocalMm = uwEq,
                mainEquivalentFocalMm = mainEq,
                switchPointMm = settings.switchPointMm
            )
        } else if (lens.lensType == LensType.ULTRAWIDE) {
            CameraOpticalCalibration.calculateFovMatchedUltraWideCrop(
                switchPointMm = settings.switchPointMm,
                mainEquivalentFocalMm = mainEq,
                uwEquivalentFocalMm = uwEq
            )
        } else {
            CameraOpticalCalibration.calculateMainCropForSwitchPoint(
                switchPointMm = settings.switchPointMm,
                mainFocalMm = mainEq
            )
        }
    }

    private fun applySynchronizedStandbySettings(builder: CaptureRequest.Builder, lens: LensInfo) {
        val mainRequest = latestMainRequest
        val mainResult = latestMainResult

        // Keep the same control modes and locks on both sensors. Unsupported keys on the
        // secondary camera are ignored independently instead of aborting the whole request.
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AE_MODE)?.let { builder.set(CaptureRequest.CONTROL_AE_MODE, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AWB_MODE)?.let { builder.set(CaptureRequest.CONTROL_AWB_MODE, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AF_MODE)?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AE_LOCK)?.let { builder.set(CaptureRequest.CONTROL_AE_LOCK, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AWB_LOCK)?.let { builder.set(CaptureRequest.CONTROL_AWB_LOCK, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) } }
        runCatching { mainRequest?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) } }

        val aeMode = mainRequest?.get(CaptureRequest.CONTROL_AE_MODE)
        if (aeMode == CameraMetadata.CONTROL_AE_MODE_OFF) {
            runCatching { mainResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) } }
            runCatching { mainResult?.get(CaptureResult.SENSOR_SENSITIVITY)?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) } }
        }

        val characteristics = try { cameraManager?.getCameraCharacteristics(lens.cameraId) } catch (_: Throwable) { null }
        val activeArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (characteristics != null && activeArray != null) {
            val ratio = calculateStandbyCropRatio(lens).coerceAtLeast(1.0f)
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            if (zoomRange != null) {
                // API 30+: set the zoom ratio and the full active-array crop together.
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(zoomRange.lower, zoomRange.upper))
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(activeArray))
            } else {
                val cropWidth = (activeArray.width() / ratio).toInt().coerceIn(1, activeArray.width())
                val cropHeight = (activeArray.height() / ratio).toInt().coerceIn(1, activeArray.height())
                val left = activeArray.left + (activeArray.width() - cropWidth) / 2
                val top = activeArray.top + (activeArray.height() - cropHeight) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
            }
        }
    }

    fun isStandbyReady(): Boolean {
        return standbyCameraDevice != null && standbyCaptureSession != null && isStreaming.get()
    }

    fun getStandbyLens(): LensInfo? = standbyLens
    fun getStandbyCameraDevice(): CameraDevice? = standbyCameraDevice
    fun getStandbyCaptureSession(): CameraCaptureSession? = standbyCaptureSession
    fun getStandbySurface(): Surface? = standbySurface

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
            return DualStreamingMode.NONE
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
            return DualStreamingMode.NONE
        }
    }

    @SuppressLint("MissingPermission")
    fun prepareSecondaryLens(mainLens: LensInfo?, secondaryLens: LensInfo?, targetSize: Size = Size(1920, 1080)) {
        if (!settings.backgroundLensPreparation) {
            release()
            return
        }
        if (secondaryLens == null || cameraManager == null) return
        this.mainLens = mainLens
        val preview = standbySurface
        if (preview == null || !preview.isValid) {
            _status.value = BackgroundCameraStatus.PREPARING
            Log.i(TAG, "[STANDBY_READY] waiting for the real Ultra-Wide TextureView surface")
            return
        }

        val dualMode = detectSimultaneousStreamingMode(mainLens, secondaryLens)
        Log.i(TAG, "[BACKGROUND_LENS] Preparing secondary lens ${secondaryLens.displayName} via dualMode=$dualMode")

        if (dualMode != DualStreamingMode.CONCURRENT_DEVICES && dualMode != DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL) {
            Log.i(TAG, "[BACKGROUND_LENS] Dual streaming unsupported for ${secondaryLens.displayName}; standby camera disabled to prevent lockup")
            _status.value = BackgroundCameraStatus.UNAVAILABLE
            return
        }

        // CameraX owns the main logical device. Opening a second Camera2 device with the same
        // logical camera ID is not a real standby stream; never report it as ready without frames.
        if (dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL && mainLens?.cameraId == secondaryLens.cameraId) {
            _status.value = BackgroundCameraStatus.UNAVAILABLE
            Log.w(TAG, "[STANDBY_READY] logical physical stream needs a Camera2 main session; sequential fallback required")
            return
        }

        // Separate CameraDevices
        val existingCam = standbyCameraDevice
        if (existingCam != null && existingCam.id == secondaryLens.cameraId && standbyCaptureSession != null && isStreaming.get()) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            Log.i(TAG, "[BACKGROUND_LENS] Reusing already prepared background stream on camera ${existingCam.id}")
            return
        }

        if (!isPreparingStandby.compareAndSet(false, true)) {
            return
        }

        _status.value = BackgroundCameraStatus.PREPARING
        val openToken = standbyDeviceGen.incrementAndGet()

        try {
            val surface = standbySurface?.takeIf { it.isValid } ?: run {
                isPreparingStandby.set(false)
                _status.value = BackgroundCameraStatus.UNAVAILABLE
                Log.w(TAG, "Standby surface is unavailable; refusing dummy-surface fallback")
                return
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
                    val errDesc = when (error) {
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE (hardware concurrency limit)"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                        else -> "error code $error"
                    }
                    Log.w(TAG, "[BACKGROUND_LENS] Standby camera ${camera.id} error: $errDesc")
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
        val surf = standbySurface?.takeIf { it.isValid } ?: run {
            isPreparingStandby.set(false)
            _status.value = BackgroundCameraStatus.UNAVAILABLE
            return
        }
        val configToken = standbySessionGen.incrementAndGet()

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }.also { applySynchronizedStandbySettings(it, lens) }

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
                    // Remain in PREPARING until the first valid frame arrives in onCaptureCompleted
                    _status.value = BackgroundCameraStatus.PREPARING

                    try {
                        session.setRepeatingRequest(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                // CaptureResult confirms the repeating request is running. The
                                // actual ready signal is TextureView.onSurfaceTextureUpdated().
                            }
                        }, backgroundHandler ?: mainHandler)
                        Log.i(TAG, "[BACKGROUND_LENS] Standby session configured on camera ID ${camera.id} (${lens.displayName}). Awaiting preview frames...")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed repeating request on standby camera ${camera.id}", e)
                        _status.value = BackgroundCameraStatus.UNAVAILABLE
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

        if (ownsStandbySurface) {
            try { standbySurface?.release() } catch (_: Throwable) {}
            standbySurface = null
            ownsStandbySurface = false
        }

        standbyLens = null
    }
}
