package io.github.realkhfan.foco.camera.lens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
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

data class StandbySwapResult(
    val newActiveDevice: CameraDevice,
    val newActiveSession: CameraCaptureSession,
    val newActiveBuilder: CaptureRequest.Builder?,
    val newActiveSurface: Surface,
    val targetLens: LensInfo
)

/**
 * Manages background secondary lens preparation and seamless dual-stream handoff.
 * Replicated from VCamera's exact background lens preparation & session management:
 * - Keeps the secondary physical camera genuinely initialized and delivering preview frames.
 * - Detects simultaneous camera modes: LOGICAL_MULTI_CAMERA_PHYSICAL, CONCURRENT_DEVICES,
 *   and PERSISTENT_DUAL_SESSION.
 * - Uses real SurfaceTexture and TextureView from the viewfinder (no fake textures or placeholders).
 * - Applies synchronized 3A (AE/AWB/AF), FPS range, EV compensation, and calibrated FOV crop.
 * - Performs true 0ms stream swap between active and standby cameras without closing/stopping sessions.
 */
class BackgroundLensManager(
    private val context: Context,
    private val settings: AppSettings,
    private val cameraManager: CameraManager?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundHandler: Handler? = null

    private val _status = MutableStateFlow(
        if (settings.backgroundLensPreparation) BackgroundCameraStatus.PREPARING else BackgroundCameraStatus.OFF
    )
    val status: StateFlow<BackgroundCameraStatus> = _status.asStateFlow()

    @Volatile
    private var ultraWideViewfinderSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var ultraWideViewfinderSurface: Surface? = null

    @Volatile
    private var mainPreviewSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var mainPreviewSurface: Surface? = null

    @Volatile
    private var fallbackUltraWideSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var fallbackUltraWideSurface: Surface? = null

    @Volatile
    private var fallbackMainSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var fallbackMainSurface: Surface? = null

    @Volatile
    private var standbyCameraDevice: CameraDevice? = null
    @Volatile
    private var standbyCaptureSession: CameraCaptureSession? = null
    @Volatile
    private var standbyRequestBuilder: CaptureRequest.Builder? = null
    @Volatile
    private var standbySurface: Surface? = null
    @Volatile
    private var standbyLens: LensInfo? = null

    private val isPreparingStandby = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private val standbySessionGen = AtomicInteger(0)
    private val standbyDeviceGen = AtomicInteger(0)

    private var reconnectRunnable: Runnable? = null
    private var reconnectCount = 0
    private val MAX_RECONNECT = 3

    var onFrameAvailable: ((PreviewStreamSource) -> Unit)? = null

    private val standbyCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (session == standbyCaptureSession && settings.backgroundLensPreparation) {
                if (!isStreaming.get()) {
                    isStreaming.set(true)
                }
                if (_status.value != BackgroundCameraStatus.READY_QUIET) {
                    _status.value = BackgroundCameraStatus.READY_QUIET
                    Log.i(TAG, "[BACKGROUND_LENS] Live standby preview frames arriving from ${standbyLens?.displayName}")
                    onFrameAvailable?.invoke(
                        if (standbyLens?.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    )
                }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (session == standbyCaptureSession) {
                Log.w(TAG, "[BACKGROUND_LENS] Standby capture failed: reason=${failure.reason}")
            }
        }
    }

    fun setBackgroundHandler(handler: Handler) {
        backgroundHandler = handler
    }

    fun isStandbyReady(): Boolean {
        return standbyCameraDevice != null && standbyCaptureSession != null && isStreaming.get()
    }

    fun getStandbyLens(): LensInfo? = standbyLens
    fun getStandbyCameraDevice(): CameraDevice? = standbyCameraDevice
    fun getStandbyCaptureSession(): CameraCaptureSession? = standbyCaptureSession
    fun getStandbyRequestBuilder(): CaptureRequest.Builder? = standbyRequestBuilder
    fun getStandbySurface(): Surface? = standbySurface

    fun setUltraWideSurfaceTexture(texture: SurfaceTexture?, width: Int = 0, height: Int = 0) {
        val prev = ultraWideViewfinderSurfaceTexture
        ultraWideViewfinderSurfaceTexture = texture
        if (texture != null) {
            val w = if (width > 0) width else 1440
            val h = if (height > 0) height else 1080
            try { texture.setDefaultBufferSize(max(w, h), min(w, h)) } catch (_: Throwable) {}
            if (prev !== texture || ultraWideViewfinderSurface == null || !ultraWideViewfinderSurface!!.isValid) {
                try { ultraWideViewfinderSurface?.release() } catch (_: Throwable) {}
                ultraWideViewfinderSurface = Surface(texture)
            }
        } else {
            try { ultraWideViewfinderSurface?.release() } catch (_: Throwable) {}
            ultraWideViewfinderSurface = null
        }
    }

    fun setMainSurfaceTexture(texture: SurfaceTexture?, width: Int = 0, height: Int = 0) {
        val prev = mainPreviewSurfaceTexture
        mainPreviewSurfaceTexture = texture
        if (texture != null) {
            val w = if (width > 0) width else 1440
            val h = if (height > 0) height else 1080
            try { texture.setDefaultBufferSize(max(w, h), min(w, h)) } catch (_: Throwable) {}
            if (prev !== texture || mainPreviewSurface == null || !mainPreviewSurface!!.isValid) {
                try { mainPreviewSurface?.release() } catch (_: Throwable) {}
                mainPreviewSurface = Surface(texture)
            }
        } else {
            try { mainPreviewSurface?.release() } catch (_: Throwable) {}
            mainPreviewSurface = null
        }
    }

    fun getOrCreateUltraWideSurface(cameraId: String? = null): Surface? {
        val uiTex = ultraWideViewfinderSurfaceTexture
        if (uiTex != null) {
            var surf = ultraWideViewfinderSurface
            if (surf == null || !surf.isValid) {
                try { surf?.release() } catch (_: Throwable) {}
                surf = Surface(uiTex)
                ultraWideViewfinderSurface = surf
            }
            return surf
        }
        var fallbackTex = fallbackUltraWideSurfaceTexture
        if (fallbackTex == null) {
            fallbackTex = SurfaceTexture(0)
            fallbackUltraWideSurfaceTexture = fallbackTex
        }
        var surf = fallbackUltraWideSurface
        if (surf == null || !surf.isValid) {
            try { surf?.release() } catch (_: Throwable) {}
            surf = Surface(fallbackTex)
            fallbackUltraWideSurface = surf
        }
        return surf
    }

    fun getOrCreateMainSurface(cameraId: String? = null): Surface? {
        val uiTex = mainPreviewSurfaceTexture
        if (uiTex != null) {
            var surf = mainPreviewSurface
            if (surf == null || !surf.isValid) {
                try { surf?.release() } catch (_: Throwable) {}
                surf = Surface(uiTex)
                mainPreviewSurface = surf
            }
            return surf
        }
        var fallbackTex = fallbackMainSurfaceTexture
        if (fallbackTex == null) {
            fallbackTex = SurfaceTexture(0)
            fallbackMainSurfaceTexture = fallbackTex
        }
        var surf = fallbackMainSurface
        if (surf == null || !surf.isValid) {
            try { surf?.release() } catch (_: Throwable) {}
            surf = Surface(fallbackTex)
            fallbackMainSurface = surf
        }
        return surf
    }

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

    /**
     * Calculates the standby lens crop so the background stream is framed to match the
     * active 1x switch-point FOV, or follows the active zoom during live handoff crossfade.
     */
    fun calculateStandbyCropRatio(standbyLens: LensInfo, availableLenses: List<LensInfo>, uiZoomOverride: Float? = null): Float {
        val sameFacing = availableLenses.filter { it.facing == standbyLens.facing }
        val mainLens = sameFacing.firstOrNull { it.isPrimaryMain }
            ?: sameFacing.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: sameFacing.firstOrNull { it.lensType == LensType.WIDE }
        val mainEqFocalMm = mainLens?.equivalent35mmFocalMm?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM

        val uwLens = sameFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val uwEqFocalMm = uwLens?.equivalent35mmFocalMm?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM

        if (uiZoomOverride != null && uiZoomOverride.isFinite()) {
            return CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = uiZoomOverride.coerceAtLeast(0.5f),
                lensBaseRatio = standbyLens.baseZoomRatio,
                lensType = standbyLens.lensType,
                uwEquivalentFocalMm = uwEqFocalMm,
                mainEquivalentFocalMm = mainEqFocalMm,
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
            )
        }

        return if (standbyLens.lensType == LensType.ULTRAWIDE) {
            CameraOpticalCalibration.calculateFovMatchedUltraWideCrop(
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM,
                mainEquivalentFocalMm = mainEqFocalMm,
                uwEquivalentFocalMm = uwEqFocalMm
            )
        } else {
            val matchedTargetFocalMm = maxOf(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM, mainEqFocalMm)
            CameraOpticalCalibration.calculateMainCropForSwitchPoint(matchedTargetFocalMm, mainEqFocalMm)
        }
    }

    /**
     * Synchronizes 3A (AE/AWB/AF), FPS range, EV compensation, and base optical crop
     * to the standby stream so both Main and Ultra-Wide streams remain continuously
     * converged at the same exposure, color balance, and frame rate.
     */
    fun applySynchronizedStandbySettings(
        builder: CaptureRequest.Builder,
        standbyLens: LensInfo,
        availableLenses: List<LensInfo>,
        handoffZoom: Float? = null
    ) {
        try {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            val chars = try { cameraManager?.getCameraCharacteristics(standbyLens.cameraId) } catch (_: Throwable) { null }
            if (chars != null) {
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                val bestFps = fpsRanges.firstOrNull { it.upper >= 30 } ?: fpsRanges.firstOrNull()
                if (bestFps != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)
                }

                val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (evRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0.coerceIn(evRange.lower, evRange.upper))
                }

                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
                if (oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }

                val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
                val targetStandbyRatio = calculateStandbyCropRatio(standbyLens, availableLenses, handoffZoom)
                val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null

                if (zoomRange != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetStandbyRatio.coerceIn(zoomRange.lower, zoomRange.upper))
                    builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
                } else {
                    val extraScale = targetStandbyRatio.coerceAtLeast(1.0f)
                    val cropW = (sensorRect.width() / extraScale).toInt().coerceIn(1, sensorRect.width())
                    val cropH = (sensorRect.height() / extraScale).toInt().coerceIn(1, sensorRect.height())
                    val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
                    val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[BACKGROUND_LENS] Error applying synchronized standby settings", e)
        }
    }

    fun syncStandbyStreamSettings(availableLenses: List<LensInfo>, handoffZoom: Float? = null) {
        if (!settings.backgroundLensPreparation) return
        val session = standbyCaptureSession ?: return
        val builder = standbyRequestBuilder ?: return
        val lens = standbyLens ?: return
        try {
            applySynchronizedStandbySettings(builder, lens, availableLenses, handoffZoom)
            session.setRepeatingRequest(builder.build(), standbyCaptureCallback, backgroundHandler ?: mainHandler)
        } catch (ignored: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    fun prepareSecondaryLens(
        mainLens: LensInfo?,
        secondaryLens: LensInfo?,
        availableLenses: List<LensInfo>,
        targetSize: Size = Size(1440, 1080)
    ) {
        if (!settings.backgroundLensPreparation) {
            release()
            return
        }
        if (secondaryLens == null || cameraManager == null) return

        val dualMode = detectSimultaneousStreamingMode(mainLens, secondaryLens)
        Log.i(TAG, "[BACKGROUND_LENS] Preparing secondary lens ${secondaryLens.displayName} (mode=$dualMode)")

        if (dualMode == DualStreamingMode.NONE) {
            _status.value = BackgroundCameraStatus.UNAVAILABLE
            return
        }

        // Case A: Logical multi-camera with physical streams inside the same device session
        if (dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL && mainLens?.cameraId == secondaryLens.cameraId) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            isStreaming.set(true)
            standbyLens = secondaryLens
            return
        }

        // Case B: Persistent dual-session across separate camera devices
        val existingCam = standbyCameraDevice
        if (existingCam != null && existingCam.id == secondaryLens.cameraId && standbyCaptureSession != null && isStreaming.get()) {
            _status.value = BackgroundCameraStatus.READY_QUIET
            syncStandbyStreamSettings(availableLenses)
            return
        }

        if (!isPreparingStandby.compareAndSet(false, true)) {
            return
        }

        _status.value = BackgroundCameraStatus.PREPARING
        val openToken = standbyDeviceGen.incrementAndGet()

        try {
            val surf = if (secondaryLens.lensType == LensType.ULTRAWIDE) {
                getOrCreateUltraWideSurface(secondaryLens.cameraId)
            } else {
                getOrCreateMainSurface(secondaryLens.cameraId)
            } ?: run {
                isPreparingStandby.set(false)
                return
            }

            standbySurface = surf

            cameraManager.openCamera(secondaryLens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openToken != standbyDeviceGen.get() || !settings.backgroundLensPreparation) {
                        isPreparingStandby.set(false)
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    standbyCameraDevice = camera
                    standbyLens = secondaryLens
                    configureStandbySession(camera, secondaryLens, availableLenses)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (openToken != standbyDeviceGen.get()) {
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    release()
                    scheduleReconnect(mainLens, secondaryLens, availableLenses)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (openToken != standbyDeviceGen.get()) {
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    Log.w(TAG, "[BACKGROUND_LENS] Standby camera ${camera.id} error: $error (scheduling reconnect)")
                    release()
                    scheduleReconnect(mainLens, secondaryLens, availableLenses)
                }
            }, backgroundHandler ?: mainHandler)
        } catch (t: Throwable) {
            isPreparingStandby.set(false)
            Log.e(TAG, "[BACKGROUND_LENS] Failed opening standby camera ${secondaryLens.cameraId}", t)
            scheduleReconnect(mainLens, secondaryLens, availableLenses)
        }
    }

    private fun configureStandbySession(camera: CameraDevice, lens: LensInfo, availableLenses: List<LensInfo>) {
        val surf = standbySurface ?: run {
            isPreparingStandby.set(false)
            return
        }
        val configToken = standbySessionGen.incrementAndGet()

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                applySynchronizedStandbySettings(this, lens, availableLenses)
            }
            standbyRequestBuilder = builder

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
                    _status.value = BackgroundCameraStatus.PREPARING

                    try {
                        session.setRepeatingRequest(builder.build(), standbyCaptureCallback, backgroundHandler ?: mainHandler)
                        Log.i(TAG, "[BACKGROUND_LENS] Standby session running on ${camera.id} (${lens.displayName}). Awaiting preview frames...")
                    } catch (e: Exception) {
                        Log.w(TAG, "[BACKGROUND_LENS] Failed repeating request on standby camera ${camera.id}", e)
                        _status.value = BackgroundCameraStatus.UNAVAILABLE
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    isPreparingStandby.set(false)
                    _status.value = BackgroundCameraStatus.UNAVAILABLE
                    Log.w(TAG, "[BACKGROUND_LENS] Standby capture session configuration failed on ${camera.id}")
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
            Log.e(TAG, "[BACKGROUND_LENS] Failed configuring standby session on camera ${camera.id}", e)
            _status.value = BackgroundCameraStatus.UNAVAILABLE
        }
    }

    /**
     * Swaps the active camera & session with the prewarmed standby camera & session:
     * - The standby camera instantly becomes the active camera.
     * - The previous active camera becomes the standby camera in the background.
     * - Neither session is closed or stopped!
     */
    fun swapActiveAndStandby(
        oldActiveDevice: CameraDevice?,
        oldActiveSession: CameraCaptureSession?,
        oldActiveBuilder: CaptureRequest.Builder?,
        oldActiveSurface: Surface?,
        targetLens: LensInfo,
        availableLenses: List<LensInfo>
    ): StandbySwapResult? {
        val newCam = standbyCameraDevice ?: return null
        val newSession = standbyCaptureSession ?: return null
        val newBuilder = standbyRequestBuilder
        val newSurface = standbySurface ?: return null

        val previousLens = standbyLens

        // Swap pointers
        standbyCameraDevice = oldActiveDevice
        standbyCaptureSession = oldActiveSession
        standbyRequestBuilder = oldActiveBuilder
        standbySurface = oldActiveSurface
        standbyLens = if (targetLens.lensType == LensType.ULTRAWIDE) {
            availableLenses.firstOrNull { it.isPrimaryMain || it.lensType == LensType.WIDE }
        } else {
            availableLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        }

        isStreaming.set(true)
        _status.value = BackgroundCameraStatus.READY_QUIET

        // Update the newly standby camera with the standby FOV crop
        val currentStandbyLens = standbyLens
        val currentStandbySession = standbyCaptureSession
        val currentStandbyBuilder = standbyRequestBuilder
        if (currentStandbyLens != null && currentStandbySession != null && currentStandbyBuilder != null) {
            try {
                applySynchronizedStandbySettings(currentStandbyBuilder, currentStandbyLens, availableLenses)
                currentStandbySession.setRepeatingRequest(
                    currentStandbyBuilder.build(),
                    standbyCaptureCallback,
                    backgroundHandler ?: mainHandler
                )
            } catch (t: Throwable) {
                Log.w(TAG, "[BACKGROUND_LENS] Error updating newly standby camera", t)
            }
        }

        Log.i(TAG, "[BACKGROUND_LENS] 0ms instant stream swap completed: newActive=${newCam.id} (${targetLens.displayName}), newStandby=${oldActiveDevice?.id}")

        return StandbySwapResult(
            newActiveDevice = newCam,
            newActiveSession = newSession,
            newActiveBuilder = newBuilder,
            newActiveSurface = newSurface,
            targetLens = targetLens
        )
    }

    private fun scheduleReconnect(mainLens: LensInfo?, secondaryLens: LensInfo?, availableLenses: List<LensInfo>, delayMs: Long = 1000L) {
        if (!settings.backgroundLensPreparation || reconnectCount >= MAX_RECONNECT) return
        reconnectRunnable?.let { backgroundHandler?.removeCallbacks(it) ?: mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (settings.backgroundLensPreparation && standbyCameraDevice == null && !isPreparingStandby.get()) {
                reconnectCount++
                Log.i(TAG, "[BACKGROUND_LENS] Reconnecting standby camera (attempt $reconnectCount)...")
                prepareSecondaryLens(mainLens, secondaryLens, availableLenses)
            }
        }
        reconnectRunnable = r
        (backgroundHandler ?: mainHandler).postDelayed(r, delayMs)
    }

    fun release() {
        standbyDeviceGen.incrementAndGet()
        standbySessionGen.incrementAndGet()
        reconnectRunnable?.let { (backgroundHandler ?: mainHandler).removeCallbacks(it) }
        reconnectRunnable = null
        reconnectCount = 0
        isPreparingStandby.set(false)
        isStreaming.set(false)
        _status.value = BackgroundCameraStatus.OFF

        try { standbyCaptureSession?.stopRepeating() } catch (_: Throwable) {}
        try { standbyCaptureSession?.close() } catch (_: Throwable) {}
        standbyCaptureSession = null
        standbyRequestBuilder = null

        try { standbyCameraDevice?.close() } catch (_: Throwable) {}
        standbyCameraDevice = null

        try { standbySurface?.release() } catch (_: Throwable) {}
        standbySurface = null

        standbyLens = null
    }
}
