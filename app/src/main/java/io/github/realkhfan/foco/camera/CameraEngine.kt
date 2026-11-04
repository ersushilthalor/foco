package io.github.realkhfan.foco.camera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import io.github.realkhfan.foco.camera.lens.BackgroundLensManager
import io.github.realkhfan.foco.camera.lens.CameraDiscovery
import io.github.realkhfan.foco.camera.lens.CameraInventory
import io.github.realkhfan.foco.camera.lens.CameraOpticalCalibration
import io.github.realkhfan.foco.camera.lens.LensDebugLog
import io.github.realkhfan.foco.camera.lens.LensInfo
import io.github.realkhfan.foco.camera.lens.LensSwitchStrategy
import io.github.realkhfan.foco.camera.lens.LensType
import io.github.realkhfan.foco.camera.lens.PreviewStreamSource
import io.github.realkhfan.foco.camera.lens.ZoomTransitionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.github.realkhfan.foco.media.MediaNaming
import io.github.realkhfan.foco.processing.BurstSession
import io.github.realkhfan.foco.processing.JpegSplice
import io.github.realkhfan.foco.processing.PhotoMeta
import io.github.realkhfan.foco.processing.PhotoWriter
import io.github.realkhfan.foco.processing.PanoramaSession
import io.github.realkhfan.foco.ui.PhotoMode
import io.github.realkhfan.foco.processing.ShotPlan
import io.github.realkhfan.foco.processing.ShotPlanner
import io.github.realkhfan.foco.processing.ToneSettings
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.FrameRate
import io.github.realkhfan.foco.settings.RatioSetting
import io.github.realkhfan.foco.settings.ResolutionSetting
import io.github.realkhfan.foco.settings.VideoResolution
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.CaptureKind
import io.github.realkhfan.foco.ui.IntentMode
import io.github.realkhfan.foco.ui.Readout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns CameraX: binds the right use cases for the current mode, probes what the phone allows,
 * applies zoom, flash and the Pro controls, and runs Foco's multi-frame photos.
 * All public methods are called on the main thread.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(
    private val context: Context,
    private val state: AppState,
    private val settings: AppSettings,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "foco-analysis") }

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var photoFormat = ImageCapture.OUTPUT_FORMAT_JPEG
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null

    private var targetRotation = Surface.ROTATION_0
    /** Zoom shown to the user = CameraX zoom × [zoomFactor] (≠ 1 only when bound to one physical lens). */
    private var zoomFactor = 1f
    private var boundPhysicalId: String? = null
    private var boundCameraId: String? = null
    private var targetDiscoveredLens: LensInfo? = null
    private val isSwitchingLens = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lensSwitchGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastLensSwitchStartTimeMs = 0L
    @Volatile private var pendingLensWhileSwitching: LensInfo? = null
    @Volatile private var pendingZoomWhileSwitching: Float? = null
    @Volatile private var pendingIsContinuousTransitionWhileSwitching = false
    @Volatile private var pendingZoomPresetTapWhileSwitching = false
    @Volatile private var pendingZoomUpdateTarget = 1f
    private var pendingZoomRunnable: Runnable? = null
    private var lastMainPreviewFrameAtMs = 0L
    private var lastUltraWidePreviewFrameAtMs = 0L
    private var lensSwitchRequestedAtMs = 0L
    private var crossfadeStartLogged = false
    private var lastZoomPreviewUpdateTimeMs = 0L
    private var firstTargetFrameArrived = false
    private var desiredZoom = 1f
    private var logicalMinZoom = 1f
    private var logicalMaxZoom = 1f
    private var zoomAnimator: ValueAnimator? = null

    val cameraManager: CameraManager? by lazy { context.getSystemService(CameraManager::class.java) }
    var inventory: CameraInventory = CameraInventory(emptyList())
        private set
    val backgroundLensManager by lazy {
        BackgroundLensManager(context, settings, cameraManager).apply {
            setBackgroundHandler(camera2Handler)
        }
    }
    var activeDiscoveredLens: LensInfo? = null
        private set
    private val engineScope = CoroutineScope(Dispatchers.Main.immediate + Job())

    val zoomTransitionController = ZoomTransitionController(
        onApplyZoomToEngine = { zoom, isContinuous ->
            mainHandler.post {
                val backLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
                val mainWideLens = backLenses.firstOrNull { it.isPrimaryMain }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
                val switchZoom = CameraOpticalCalibration.switchPointToZoom(settings.switchPointMm)
                val targetLens = if (zoom < switchZoom && ultraWideLens != null) ultraWideLens else (mainWideLens ?: activeDiscoveredLens)
                if (targetLens != null && isSwitchingLens.get()) {
                    // Record the latest desired lens while keeping the zoom timeline running.
                    selectDiscoveredLens(targetLens, zoom, isContinuous)
                } else if (targetLens != null && targetLens.lensType != activeDiscoveredLens?.lensType) {
                    selectDiscoveredLens(targetLens, zoom, isContinuous)
                } else {
                    applyZoomInternal(zoom, isContinuous)
                }
            }
        },
        onSelectLensOnEngine = { lens, targetZoom, isContinuous ->
            mainHandler.post { selectDiscoveredLens(lens, targetZoom, isContinuous) }
        }
    )

    @Volatile private var meteredShutterNs = 16_666_666L
    @Volatile private var meteredIso = 100
    @Volatile private var lastAperture: Float? = null
    @Volatile private var lastFocalMm: Float? = null
    @Volatile private var activeLensRatio = 1f
    @Volatile private var clippedFraction = 0f
    private var lastReadoutMs = 0L
    private var lastQrScan = 0L
    private var lastQrHit = 0L
    private var lastStats = 0L
    private var lastHazeCheck = 0L
    private var hazeStrikes = 0
    private var lastHazeWarning = 0L

    /** Exposure/ISO of recent frames by sensor timestamp, so a burst can tell which frames have new settings. */
    private val frameMeta = ConcurrentHashMap<Long, LongArray>()

    @Volatile private var burst: BurstSession? = null
    @Volatile private var pano: PanoramaSession? = null
    /** Camera settings while a burst runs: a fixed exposure (Night, highlight frame) or locked auto exposure. */
    private data class BurstCamera(val exposureNs: Long?, val iso: Int?, val frameNs: Long = 33_333_333L)
    private var burstOverride: BurstCamera? = null
    private var highlightRequested = false
    private var burstTimeout: Runnable? = null

    /**
     * Stable parent for the Camera2 TextureView. It must be a sibling of PreviewView, not its child:
     * PreviewView manages/replaces its own internal children as CameraX binds and unbinds use cases.
     */
    var camera2PreviewHost: FrameLayout? = null
        set(value) {
            field = value
            if (value != null) ensureCamera2TextureView(value)
        }

    private var cameraXPreviewTextureView: TextureView? = null
    private var cameraXPreviewOriginalListener: TextureView.SurfaceTextureListener? = null
    private var cameraXPreviewWrappedListener: TextureView.SurfaceTextureListener? = null
    private var cameraXPreviewLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    private val _activePreviewSource = MutableStateFlow(PreviewStreamSource.MAIN)
    val activePreviewSource: StateFlow<PreviewStreamSource> = _activePreviewSource.asStateFlow()

    var previewView: PreviewView? = null
        set(value) {
            field = value
            if (value != null) {
                // COMPATIBLE uses CameraX's TextureView implementation, allowing the real buffer
                // arrival callback to be forwarded without replacing CameraX's own listener.
                value.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                value.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    attachCameraXFrameListener()
                }
                val observer = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (attachCameraXFrameListener()) {
                            cameraXPreviewLayoutListener = null
                            if (value.viewTreeObserver.isAlive) {
                                value.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            }
                        }
                    }
                }
                cameraXPreviewLayoutListener = observer
                value.viewTreeObserver.addOnGlobalLayoutListener(observer)
                value.post { attachCameraXFrameListener() }
                val host = camera2PreviewHost ?: (value.parent as? FrameLayout)
                host?.let { ensureCamera2TextureView(it) }
                if (provider != null && camera == null && !isCamera2Active) rebind()
            }
        }

    private fun findPreviewTextureView(view: android.view.View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreviewTextureView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun attachCameraXFrameListener(): Boolean {
        val host = previewView ?: return false
        val textureView = findPreviewTextureView(host) ?: return false
        if (textureView === cameraXPreviewTextureView &&
            textureView.surfaceTextureListener === cameraXPreviewWrappedListener
        ) return true

        val original = textureView.surfaceTextureListener ?: return false
        val wrapper = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                original.onSurfaceTextureAvailable(surface, width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                original.onSurfaceTextureSizeChanged(surface, width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return original.onSurfaceTextureDestroyed(surface)
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                original.onSurfaceTextureUpdated(surface)
                // CameraX always owns the main preview stream in this hybrid architecture.
                onViewfinderFrameAvailable(PreviewStreamSource.MAIN)
            }
        }
        textureView.surfaceTextureListener = wrapper
        cameraXPreviewTextureView = textureView
        cameraXPreviewOriginalListener = original
        cameraXPreviewWrappedListener = wrapper
        return true
    }

    private fun onViewfinderFrameAvailable(source: PreviewStreamSource) {
        val now = SystemClock.uptimeMillis()
        val previousFrameAt = if (source == PreviewStreamSource.MAIN) lastUltraWidePreviewFrameAtMs else lastMainPreviewFrameAtMs
        if (source == PreviewStreamSource.MAIN) lastMainPreviewFrameAtMs = now else lastUltraWidePreviewFrameAtMs = now
        backgroundLensManager.onPreviewFrameAvailable(source)

        val target = targetDiscoveredLens
        val expectedSource = if (target?.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        if (isSwitchingLens.get() && target != null && source == expectedSource) {
            val elapsed = if (lensSwitchRequestedAtMs > 0L) (now - lensSwitchRequestedAtMs).coerceAtLeast(0L) else -1L
            firstTargetFrameArrived = true
            cancelCamera2Watchdog()
            activeDiscoveredLens = target
            targetDiscoveredLens = null
            _activePreviewSource.value = source
            backgroundLensManager.updateZoom(state.zoom, handoffActive = zoomTransitionController.previewOverlapState.value.isOverlapping, ultraWideIsActive = source == PreviewStreamSource.ULTRAWIDE)
            Log.i(TAG, "[LENS_SWITCH_LATENCY] tapToFirstFreshTargetFrameMs=$elapsed target=${target.displayName} source=$source")
            Log.i(TAG, "[LENS_SWITCH_SUCCESS] First fresh viewfinder buffer displayed for ${target.displayName}")
            LensDebugLog.log("first fresh viewfinder buffer arrived for ${target.displayName} after ${elapsed}ms")
            completeLensSwitch(target)
        }

        val overlapBefore = zoomTransitionController.previewOverlapState.value
        if (overlapBefore.isOverlapping && !crossfadeStartLogged) {
            val age = if (previousFrameAt > 0L) (now - previousFrameAt).coerceAtLeast(0L) else -1L
            Log.i(TAG, "[LENS_CROSSFADE] source=$source previousBufferAgeMs=$age; standbyReady=${backgroundLensManager.isStandbyReady()}")
            crossfadeStartLogged = true
        } else if (!overlapBefore.isOverlapping) {
            crossfadeStartLogged = false
        }

        val wasOverlapping = overlapBefore.isOverlapping
        zoomTransitionController.onPreviewFrameAvailable(source)
        val isOverlappingNow = zoomTransitionController.previewOverlapState.value.isOverlapping
        if (wasOverlapping && !isOverlappingNow) {
            backgroundLensManager.updateZoom(state.zoom, handoffActive = false, ultraWideIsActive = _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE)
        }
    }

    fun updatePreviewOverlapState(
        overlap: io.github.realkhfan.foco.camera.lens.PreviewOverlapState,
        activeSource: PreviewStreamSource
    ) {
        val applyState = {
            val mainView = previewView
            val ultraWideView = camera2TextureView
            val overlapping = overlap.isOverlapping
            val mainOnTop = if (overlapping) overlap.topSource == PreviewStreamSource.MAIN
                else activeSource == PreviewStreamSource.MAIN
            val ultraWideOnTop = if (overlapping) overlap.topSource == PreviewStreamSource.ULTRAWIDE
                else activeSource == PreviewStreamSource.ULTRAWIDE

            mainView?.apply {
                val mainVisible = activeSource == PreviewStreamSource.MAIN
                alpha = if (overlapping) overlap.mainAlpha else if (mainVisible) 1f else 0f
                translationZ = if (mainOnTop) 2f else if (overlapping) 1f else 0f
                z = translationZ
                // Keep both TextureViews VISIBLE (including alpha=0) so standby buffers keep arriving.
                visibility = android.view.View.VISIBLE
            }
            ultraWideView?.apply {
                val ultraWideVisible = activeSource == PreviewStreamSource.ULTRAWIDE
                alpha = if (overlapping) overlap.ultraWideAlpha else if (ultraWideVisible) 1f else 0f
                translationZ = if (ultraWideOnTop) 2f else if (overlapping) 1f else 0f
                z = translationZ
                visibility = android.view.View.VISIBLE
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) applyState()
        else mainHandler.post { applyState() }
    }

    // ------------------------------------------------------------------ Camera2 Engine for Hidden / Auxiliary Lenses
    @Volatile private var isCamera2Active = false
    @Volatile private var camera2Device: CameraDevice? = null
    @Volatile private var camera2Session: CameraCaptureSession? = null
    private var camera2RequestBuilder: CaptureRequest.Builder? = null
    private var camera2JpegReader: ImageReader? = null
    private var camera2YuvReader: ImageReader? = null
    @Volatile private var camera2Surface: Surface? = null
    private var camera2TextureView: TextureView? = null
    private val camera2Thread = HandlerThread("foco-camera2").apply { start() }
    private val camera2Handler = Handler(camera2Thread.looper)
    private var camera2DeviceGen = java.util.concurrent.atomic.AtomicInteger(0)
    private var camera2SessionGen = java.util.concurrent.atomic.AtomicInteger(0)
    private var camera2PendingStillCapture: ((Uri?, Throwable?) -> Unit)? = null
    private var camera2PendingStillFile: File? = null
    private var camera2ComboStage = 1
    private var camera2PreviewSize: Size = Size(1440, 1080)
    private var camera2CloseDeferred: CompletableDeferred<Unit>? = null

    private enum class Camera2WatchdogStage {
        OPEN,
        SURFACE,
        CONFIGURE,
        FIRST_FRAME
    }
    private var camera2WatchdogStage = Camera2WatchdogStage.OPEN
    private var camera2WatchdogRunnable: Runnable? = null

    private fun startCamera2Watchdog(stage: Camera2WatchdogStage, timeoutMs: Long) {
        cancelCamera2Watchdog()
        camera2WatchdogStage = stage
        val r = Runnable {
            if (isCamera2Active && !firstTargetFrameArrived) {
                val stageName = when (stage) {
                    Camera2WatchdogStage.OPEN -> "open"
                    Camera2WatchdogStage.SURFACE -> "preview surface"
                    Camera2WatchdogStage.CONFIGURE -> "configure (combo $camera2ComboStage)"
                    Camera2WatchdogStage.FIRST_FRAME -> "first frame"
                }
                LensDebugLog.log("watchdog fired: stage $stageName timed out after ${timeoutMs}ms")
                Log.w(TAG, "[CAMERA2_TIMEOUT] Watchdog fired: stage $stageName timed out after ${timeoutMs}ms on Camera2 ID ${camera2Device?.id}")
                fallbackToCameraXMain("Ultra-wide preview timed out ($stageName), returned to 1x")
            }
        }
        camera2WatchdogRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    private fun cancelCamera2Watchdog() {
        camera2WatchdogRunnable?.let {
            mainHandler.removeCallbacks(it)
            camera2WatchdogRunnable = null
        }
    }

    private fun fallbackToCameraXMain(msg: String) {
        mainHandler.post {
            LensDebugLog.log("fallbackToCameraXMain: $msg")
            cancelCamera2Watchdog()
            closeCamera2(keepTexture = false)
            targetDiscoveredLens = null
            val backLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
                ?: backLenses.firstOrNull()
            activeDiscoveredLens = mainLens
            completeLensSwitch(mainLens)
            state.zoom = 1.0f
            desiredZoom = 1.0f
            zoomFactor = 1.0f
            state.message = msg
            state.lensDebugDialogText = LensDebugLog.dump()
            Log.w(TAG, "[CAMERA2_FALLBACK] Restoring Main lens via CameraX: ${mainLens?.displayName} ($msg)")
            rebind()
        }
    }

    fun onHostStop() {
        backgroundLensManager.release()
        if (isCamera2Active) {
            LensDebugLog.log("onHostStop: closing Camera2 while preserving active lens")
            closeCamera2(keepTexture = false)
        }
    }

    fun onHostStart() {
        val currentTarget = targetDiscoveredLens ?: activeDiscoveredLens
        val requestedBack = state.lensFacing == CameraSelector.LENS_FACING_BACK
        val activeRequiresCamera2 = requestedBack && currentTarget != null && !currentTarget.isPrimaryMain && (
            currentTarget.isHiddenAux ||
            (currentTarget.isIndependentCamera && currentTarget.cameraId != "0" && currentTarget.cameraId != "1") ||
            (currentTarget.lensType == LensType.ULTRAWIDE && currentTarget.cameraId != "0")
        )
        if (settings.backgroundLensPreparation && state.lensFacing == CameraSelector.LENS_FACING_BACK) {
            backgroundLensPrepChanged()
        }
        if (activeRequiresCamera2 && !isCamera2Active) {
            LensDebugLog.log("onHostStart: re-opening Camera2 for ${currentTarget.displayName}")
            isSwitchingLens.set(true)
            targetDiscoveredLens = currentTarget
            firstTargetFrameArrived = false
            openCamera2(currentTarget)
        }
    }

    private fun updateCamera2TextureTransform(viewWidth: Int, viewHeight: Int, previewWidth: Int, previewHeight: Int) {
        val tv = camera2TextureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // In portrait (viewHeight > viewWidth), sensor dimensions are rotated 90 degrees
        val isPortrait = viewHeight > viewWidth
        val bufferW = if (isPortrait) previewHeight.toFloat() else previewWidth.toFloat()
        val bufferH = if (isPortrait) previewWidth.toFloat() else previewHeight.toFloat()

        // Match PreviewView ScaleType.FILL_CENTER
        val scale = kotlin.math.max(viewWidth.toFloat() / bufferW, viewHeight.toFloat() / bufferH)
        val scaledW = bufferW * scale
        val scaledH = bufferH * scale

        val scaleX = scaledW / viewWidth.toFloat()
        val scaleY = scaledH / viewHeight.toFloat()
        matrix.setScale(scaleX, scaleY, centerX, centerY)
        mainHandler.post {
            tv.setTransform(matrix)
        }
    }

    private fun ensureCamera2TextureView(host: FrameLayout) {
        val existing = camera2TextureView
        if (existing != null) {
            // Reparent only when the host actually changes. Avoid churning the SurfaceTexture
            // during ordinary PreviewView use-case rebinding.
            if (existing.parent !== host) {
                (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                if (existing.parent == null) {
                    host.addView(
                        existing,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
            return
        }
        val tv = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Keep the TextureView attached and its SurfaceTexture allocated even while CameraX is active.
            // GONE can prevent TextureView from creating a SurfaceTexture until after CameraDevice has opened.
            visibility = android.view.View.VISIBLE
            alpha = 0f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    LensDebugLog.log("TextureView available: ${width}x${height}")
                    st.setDefaultBufferSize(camera2PreviewSize.width, camera2PreviewSize.height)
                    camera2Surface?.release()
                    camera2Surface = Surface(st)
                    backgroundLensManager.setPreviewSurface(camera2Surface)
                    if (settings.backgroundLensPreparation) backgroundLensPrepChanged()
                    updateCamera2TextureTransform(width, height, camera2PreviewSize.width, camera2PreviewSize.height)
                    if (isCamera2Active && camera2Device != null && camera2Session == null) {
                        mainHandler.post {
                            if (isCamera2Active && camera2Device != null && camera2Session == null) {
                                cancelCamera2Watchdog()
                                createCamera2Session(comboStage = 1)
                            }
                        }
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    LensDebugLog.log("TextureView size changed: ${width}x${height}")
                    st.setDefaultBufferSize(camera2PreviewSize.width, camera2PreviewSize.height)
                    updateCamera2TextureTransform(width, height, camera2PreviewSize.width, camera2PreviewSize.height)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    LensDebugLog.log("TextureView destroyed")
                    backgroundLensManager.setPreviewSurface(null)
                    camera2Surface?.release()
                    camera2Surface = null
                    val oldSession = camera2Session
                    camera2Session = null
                    camera2SessionGen.incrementAndGet()
                    try { oldSession?.close() } catch (_: Throwable) {}
                    if (isCamera2Active && camera2Device != null) {
                        firstTargetFrameArrived = false
                        startCamera2Watchdog(Camera2WatchdogStage.SURFACE, 8000L)
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    val lens = targetDiscoveredLens ?: activeDiscoveredLens
                    val isStandbyTexture = backgroundLensManager.getStandbyCaptureSession() != null
                    val source = if (isStandbyTexture || lens?.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    onViewfinderFrameAvailable(source)
                }
            }
        }
        camera2TextureView = tv
        // This is a sibling overlay in the stable host, never a child of PreviewView.
        host.addView(tv)
    }

    /**
     * Returns a live preview Surface, recreating the lightweight Surface wrapper when TextureView
     * has been re-created by Android. Must be called on the main thread because it reads View state.
     */
    private fun resolveCamera2PreviewSurface(): Surface? {
        val textureView = camera2TextureView ?: return null
        camera2Surface?.takeIf { it.isValid }?.let { return it }
        if (!textureView.isAttachedToWindow || !textureView.isAvailable) return null
        val texture = textureView.surfaceTexture ?: return null
        return try {
            texture.setDefaultBufferSize(camera2PreviewSize.width, camera2PreviewSize.height)
            Surface(texture).also { camera2Surface = it }
        } catch (t: Throwable) {
            LensDebugLog.log("resolveCamera2PreviewSurface failed: ${t.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera2(lens: LensInfo) {
        val mgr = cameraManager ?: run {
            isSwitchingLens.set(false)
            state.message = "CameraManager not available"
            LensDebugLog.log("openCamera2 failed: CameraManager not available")
            return
        }

        LensDebugLog.log("openCamera2 called for ${lens.displayName} (cameraId=${lens.cameraId}, surface=${if (camera2Surface != null) "ready" else "null"})")

        // Do not tear down the persistent standby stream as part of a normal lens open request.
        // Sequential open is retained only for fallback routes where no compatible standby is ready.
        closeCamera2(keepTexture = true)
        camera2PreviewHost?.let { ensureCamera2TextureView(it) }
            ?: (previewView?.parent as? FrameLayout)?.let { ensureCamera2TextureView(it) }
        camera2TextureView?.apply {
            visibility = android.view.View.VISIBLE
            alpha = 1f
            bringToFront()
            requestLayout()
            invalidate()
        }

        isCamera2Active = true
        firstTargetFrameArrived = false
        startCamera2Watchdog(Camera2WatchdogStage.OPEN, 4000L)

        val openToken = camera2DeviceGen.incrementAndGet()
        Log.i(TAG, "[CAMERA2_OPEN] Opening Camera2 device ID ${lens.cameraId} (${lens.displayName}, isHiddenAux=${lens.isHiddenAux})")

        try {
            mgr.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    LensDebugLog.log("CameraDevice onOpened (ID ${device.id})")
                    if (openToken != camera2DeviceGen.get() || !isCamera2Active) {
                        try { device.close() } catch (_: Throwable) {}
                        return
                    }
                    camera2Device = device
                    boundCameraId = device.id
                    boundPhysicalId = lens.physicalCameraId
                    Log.i(TAG, "[CAMERA2_OPENED] Camera2 device ID ${device.id} opened successfully")
                    mainHandler.post {
                        if (openToken != camera2DeviceGen.get() || !isCamera2Active) return@post
                        // The OPEN watchdog has done its job. Surface creation is a separate lifecycle
                        // stage and must not be reported as a camera-open timeout.
                        cancelCamera2Watchdog()
                        if (resolveCamera2PreviewSurface() == null) {
                            LensDebugLog.log("CameraDevice opened; waiting for TextureView SurfaceTexture")
                            startCamera2Watchdog(Camera2WatchdogStage.SURFACE, 8000L)
                            camera2TextureView?.apply {
                                visibility = android.view.View.VISIBLE
                                alpha = 1f
                                requestLayout()
                                invalidate()
                            }
                        } else {
                            LensDebugLog.log("CameraDevice opened with preview Surface ready")
                            createCamera2Session(comboStage = 1)
                        }
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    LensDebugLog.log("CameraDevice onDisconnected (ID ${device.id})")
                    Log.w(TAG, "[CAMERA2_DISCONNECTED] Camera2 device ID ${device.id} disconnected")
                    if (camera2Device == device) {
                        fallbackToCameraXMain("Ultra-wide camera disconnected, returned to 1x")
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val errDesc = when (error) {
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                        else -> "error code $error"
                    }
                    LensDebugLog.log("CameraDevice onError (ID ${device.id}, error=$errDesc)")
                    Log.e(TAG, "[CAMERA2_ERROR] Camera2 device ${device.id} error: $errDesc")
                    if (camera2Device == device || camera2Device == null) {
                        fallbackToCameraXMain("Failed to open ${lens.displayName} ($errDesc), returned to 1x")
                    }
                }

                override fun onClosed(device: CameraDevice) {
                    LensDebugLog.log("CameraDevice onClosed (ID ${device.id})")
                    camera2CloseDeferred?.complete(Unit)
                }
            }, camera2Handler)
        } catch (t: Throwable) {
            LensDebugLog.log("openCamera2 exception: ${t.message}")
            Log.e(TAG, "[CAMERA2_ERROR] Exception opening Camera2 device ${lens.cameraId}", t)
            fallbackToCameraXMain("Failed to open ${lens.displayName}: ${t.message}, returned to 1x")
        }
    }

    private fun createCamera2Session(comboStage: Int = 1) {
        // TextureView state and SurfaceTexture ownership are confined to the main thread.
        // Camera2 state callbacks may arrive on camera2Handler, so marshal session setup here.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { createCamera2Session(comboStage) }
            return
        }
        val device = camera2Device ?: return
        val surf = resolveCamera2PreviewSurface()
        val lens = targetDiscoveredLens ?: activeDiscoveredLens ?: return
        val configToken = camera2SessionGen.incrementAndGet()
        camera2ComboStage = comboStage

        LensDebugLog.log("createCamera2Session: comboStage=$comboStage, camera2Surface=${if (surf?.isValid == true) "valid" else "null/invalid"}")

        if (surf == null || !surf.isValid) {
            LensDebugLog.log("createCamera2Session postponed: TextureView SurfaceTexture is not ready")
            startCamera2Watchdog(Camera2WatchdogStage.SURFACE, 8000L)
            mainHandler.post {
                camera2TextureView?.apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 1f
                    requestLayout()
                    invalidate()
                }
            }
            return
        }

        camera2JpegReader?.close()
        camera2JpegReader = null
        camera2YuvReader?.close()
        camera2YuvReader = null

        val chars = try { cameraManager?.getCameraCharacteristics(device.id) } catch (_: Throwable) { null }
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val targetAspect = if (settings.ratio == RatioSetting.WIDE_9_16) 16f / 9f else 4f / 3f

        // Preview size (guaranteed preview size <= 1080p)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.filter {
            abs(it.width.toFloat() / it.height - targetAspect) < 0.05f
        }?.sortedByDescending { it.width.toLong() * it.height } ?: emptyList()
        val previewSize = previewSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: previewSizes.lastOrNull()
            ?: Size(if (settings.ratio == RatioSetting.WIDE_9_16) 1920 else 1440, 1080)
        camera2PreviewSize = previewSize

        val tv = camera2TextureView
        if (tv != null) {
            tv.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            updateCamera2TextureTransform(tv.width, tv.height, previewSize.width, previewSize.height)
        }

        // JPEG size
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.filter {
            abs(it.width.toFloat() / it.height - targetAspect) < 0.05f
        }?.sortedByDescending { it.width.toLong() * it.height }
        val stillSize = jpegSizes?.firstOrNull() ?: Size(4000, 3000)

        // YUV size: preview-size, <= 1080p, guaranteed stream size (never max sensor size)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.filter {
            abs(it.width.toFloat() / it.height - targetAspect) < 0.05f && it.width <= 1920 && it.height <= 1080
        }?.sortedByDescending { it.width.toLong() * it.height }
        val yuvSize = yuvSizes?.firstOrNull() ?: previewSize

        val outputSurfaces = mutableListOf<Surface>()
        outputSurfaces.add(surf)

        val comboDesc: String
        when (comboStage) {
            1 -> {
                // Combo 1: [preview + JPEG + YUV(preview-size <= 1080p)]
                val jpegReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try { handleCamera2Jpeg(img) } finally { img.close() }
                    }, camera2Handler)
                }
                camera2JpegReader = jpegReader
                outputSurfaces.add(jpegReader.surface)

                val yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try { handleCamera2Yuv(img) } finally { img.close() }
                    }, camera2Handler)
                }
                camera2YuvReader = yuvReader
                outputSurfaces.add(yuvReader.surface)
                comboDesc = "Combo 1 [preview(${previewSize.width}x${previewSize.height}) + JPEG(${stillSize.width}x${stillSize.height}) + YUV(${yuvSize.width}x${yuvSize.height})]"
            }
            2 -> {
                // Combo 2: [preview + JPEG]
                val jpegReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try { handleCamera2Jpeg(img) } finally { img.close() }
                    }, camera2Handler)
                }
                camera2JpegReader = jpegReader
                outputSurfaces.add(jpegReader.surface)
                comboDesc = "Combo 2 [preview(${previewSize.width}x${previewSize.height}) + JPEG(${stillSize.width}x${stillSize.height})]"
            }
            else -> {
                // Combo 3: [preview only]
                comboDesc = "Combo 3 [preview only (${previewSize.width}x${previewSize.height})]"
            }
        }

        LensDebugLog.log("createCamera2Session: Attempting stream combo $comboStage: $comboDesc")
        Log.i(TAG, "[CAMERA2] Configuring session with $comboDesc on camera ${device.id}")
        startCamera2Watchdog(Camera2WatchdogStage.CONFIGURE, 3000L)

        try {
            val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            camera2RequestBuilder = reqBuilder

            @Suppress("DEPRECATION")
            device.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (configToken != camera2SessionGen.get()) {
                        try { session.close() } catch (_: Throwable) {}
                        return
                    }
                    camera2Session = session
                    LensDebugLog.log("CameraCaptureSession onConfigured (combo $comboStage: $comboDesc)")
                    Log.i(TAG, "[CAMERA2_SESSION] Camera2 session configured with combo $comboStage: $comboDesc")
                    applyCamera2Pro(reqBuilder)
                    applyCamera2Zoom(desiredZoom, reqBuilder)
                    startCamera2Watchdog(Camera2WatchdogStage.FIRST_FRAME, 3000L)

                    try {
                        session.setRepeatingRequest(reqBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                                if (timestamp != null && exposure != null && iso != null) {
                                    frameMeta[timestamp] = longArrayOf(exposure, iso.toLong())
                                }
                                val aeOff = request.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF
                                if (!aeOff && exposure != null && iso != null) {
                                    meteredShutterNs = exposure
                                    meteredIso = iso
                                }

                                backgroundLensManager.syncMainCaptureResult(request, result)
                                // CaptureResult is for 3A convergence only; TextureView signals fresh preview frames.

                                val now = SystemClock.uptimeMillis()
                                if (now - lastReadoutMs < 250) return
                                lastReadoutMs = now
                                val aperture = result.get(CaptureResult.LENS_APERTURE)
                                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                val shutterNow = meteredShutterNs
                                val isoNow = meteredIso
                                mainHandler.post {
                                    val rLens = when {
                                        activeDiscoveredLens?.lensType == LensType.ULTRAWIDE -> LensKind.ULTRAWIDE
                                        activeDiscoveredLens?.lensType == LensType.TELEPHOTO -> LensKind.TELEPHOTO
                                        activeDiscoveredLens?.lensType == LensType.TELEPHOTO_3X -> LensKind.TELEPHOTO
                                        state.zoom < 0.95f -> LensKind.ULTRAWIDE
                                        else -> LensKind.WIDE
                                    }
                                    state.readout = Readout(rLens, aperture, exposure, iso, focus)
                                    if (burst == null) {
                                        state.dark = ShotPlanner.isDark(shutterNow, isoNow)
                                        state.nightEstimate = ShotPlanner.autoNightSeconds(shutterNow, isoNow, ShotPlanner.motionFor(motionProvider()))
                                    }
                                }
                            }
                        }, camera2Handler)
                    } catch (e: Exception) {
                        LensDebugLog.log("setRepeatingRequest error: ${e.message}")
                        Log.e(TAG, "[CAMERA2_SESSION_ERROR] Failed setRepeatingRequest on ${device.id}", e)
                    }

                    mainHandler.post {
                        state.cameraReady = true
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    LensDebugLog.log("CameraCaptureSession onConfigureFailed (combo $comboStage on Camera2 ID ${device.id})")
                    Log.w(TAG, "[CAMERA2_SESSION_ERROR] Stream combination failed for $comboDesc on ${device.id}")
                    if (configToken != camera2SessionGen.get()) return

                    if (comboStage < 3) {
                        val nextStage = comboStage + 1
                        Log.i(TAG, "[CAMERA2] Retrying with progressive smaller stream combo: stage $nextStage")
                        createCamera2Session(nextStage)
                    } else {
                        LensDebugLog.log("All 3 stream combinations failed on Camera2 ID ${device.id}")
                        Log.e(TAG, "[CAMERA2_SESSION_ERROR] All 3 stream combinations failed on Camera2 ID ${device.id}")
                        fallbackToCameraXMain("Ultra-wide session configuration failed, returned to 1x")
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    if (camera2Session == session) {
                        camera2Session = null
                    }
                }
            }, camera2Handler)
        } catch (e: Exception) {
            LensDebugLog.log("createCaptureSession exception: ${e.message}")
            Log.e(TAG, "[CAMERA2_SESSION_ERROR] Exception creating capture session for $comboDesc on ${device.id}", e)
            if (comboStage < 3) {
                createCamera2Session(comboStage + 1)
            } else {
                fallbackToCameraXMain("Failed to start ultra-wide session: ${e.message}, returned to 1x")
            }
        }
    }

    private fun closeCamera2(keepTexture: Boolean = false) {
        cancelCamera2Watchdog()
        camera2DeviceGen.incrementAndGet()
        camera2SessionGen.incrementAndGet()
        try { camera2Session?.stopRepeating() } catch (_: Throwable) {}
        try { camera2Session?.close() } catch (_: Throwable) {}
        camera2Session = null

        val dev = camera2Device
        if (dev != null) {
            camera2CloseDeferred = CompletableDeferred()
            try { dev.close() } catch (_: Throwable) {}
            camera2Device = null
        } else {
            camera2CloseDeferred?.complete(Unit)
        }

        camera2JpegReader?.close()
        camera2JpegReader = null
        camera2YuvReader?.close()
        camera2YuvReader = null

        isCamera2Active = false
        if (!keepTexture) {
            // Keep TextureView attached so Android retains its SurfaceTexture for the next switch.
            // Alpha hides its content without destroying the preview surface (GONE can destroy it).
            mainHandler.post {
                camera2TextureView?.alpha = 0f
            }
        }
    }

    private fun applyCamera2Zoom(zoom: Float, builder: CaptureRequest.Builder? = camera2RequestBuilder) {
        val b = builder ?: return
        val device = camera2Device ?: return
        val chars = try { cameraManager?.getCameraCharacteristics(device.id) } catch (_: Throwable) { null } ?: return
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val sensorZoom = (zoom / zoomFactor).coerceAtLeast(1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, sensorZoom.coerceIn(range.lower, range.upper))
                return
            }
        }
        val cropW = (activeArray.width() / sensorZoom).toInt()
        val cropH = (activeArray.height() / sensorZoom).toInt()
        val cropX = (activeArray.width() - cropW) / 2
        val cropY = (activeArray.height() - cropH) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    private fun applyCamera2Pro(b: CaptureRequest.Builder? = camera2RequestBuilder) {
        val req = b ?: return
        val pro = state.pro
        val override = burstOverride
        if (override != null) {
            if (override.exposureNs != null && override.iso != null) {
                req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, override.exposureNs)
                req.set(CaptureRequest.SENSOR_SENSITIVITY, override.iso)
            } else {
                req.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            req.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        } else if (pro.shutterNs != null || pro.iso != null) {
            val shutter = pro.shutterNs ?: meteredShutterNs
            val iso = pro.iso ?: meteredIso
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        if (pro.focusDiopters != null) {
            req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, pro.focusDiopters)
        } else {
            req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        req.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    private fun camera2CaptureStill(toFile: File?, onResult: (Uri?, Throwable?) -> Unit) {
        val device = camera2Device ?: return onResult(null, IllegalStateException("Camera2 not open"))
        val session = camera2Session ?: return onResult(null, IllegalStateException("Camera2 session not configured"))
        val jpegReader = camera2JpegReader

        if (jpegReader == null) {
            Log.i(TAG, "[CAMERA2_STILL] JPEG reader not present; capturing frame from TextureView bitmap")
            val tv = camera2TextureView
            val bmp = tv?.bitmap
            if (bmp != null) {
                try {
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    val bytes = baos.toByteArray()
                    val uri = PhotoWriter.save(
                        context = context,
                        jpeg = bytes,
                        name = MediaNaming.baseName(System.currentTimeMillis()),
                        toFile = toFile
                    )
                    onResult(uri, null)
                } catch (t: Throwable) {
                    onResult(null, t)
                } finally {
                    bmp.recycle()
                }
                return
            } else {
                return onResult(null, IllegalStateException("Preview bitmap not available"))
            }
        }

        camera2PendingStillCapture = onResult
        camera2PendingStillFile = toFile

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegReader.surface)
                applyCamera2Pro(this)
                applyCamera2Zoom(desiredZoom, this)
                set(CaptureRequest.JPEG_ORIENTATION, targetRotation * 90)
            }
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "[CAMERA2_STILL] Capture failed: reason=${failure.reason}")
                    mainHandler.post { onResult(null, RuntimeException("Still capture failed")) }
                }
            }, camera2Handler)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2_STILL] Exception during still capture", e)
            onResult(null, e)
        }
    }

    private fun handleCamera2Jpeg(image: Image) {
        val callback = camera2PendingStillCapture ?: return
        camera2PendingStillCapture = null
        val targetFile = camera2PendingStillFile
        camera2PendingStillFile = null

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        mainHandler.post {
            try {
                val uri = PhotoWriter.save(
                    context = context,
                    jpeg = bytes,
                    name = MediaNaming.baseName(System.currentTimeMillis()),
                    toFile = targetFile
                )
                callback(uri, null)
            } catch (t: Throwable) {
                callback(null, t)
            }
        }
    }

    private fun handleCamera2Yuv(image: Image) {
        val proxy = Camera2ImageProxy(image)
        try {
            val session = burst
            if (session != null) {
                feedBurst(session, proxy)
                return
            }
            val panorama = pano
            if (panorama != null) {
                panorama.offer(proxy)
                return
            }
        } finally {
            // Buffer copied synchronously in offer
        }
    }
    var screenFlash: ImageCapture.ScreenFlash? = null
    var locationProvider: () -> Location? = { null }
    /** Phone rotation speed in rad/s (gyroscope), for picking night exposures. */
    var motionProvider: () -> Float = { 0f }
    var onLensHazy: () -> Unit = {}

    val isRecording: Boolean get() = recording != null
    /** A processed photo or panorama is being taken. */
    val isBursting: Boolean get() = burst != null || pano != null
    /** (turn, elevation, turning speed) from the gyroscope, for panoramas. */
    var panSensors: () -> FloatArray = { floatArrayOf(0f, 0f, 0f) }
    var resetPan: () -> Unit = {}

    private val qrScanner = QrScanner()

    private val zoomObserver = Observer<ZoomState> { zs ->
        if (boundPhysicalId == null) {
            logicalMinZoom = zs.minZoomRatio
            logicalMaxZoom = zs.maxZoomRatio
        }
        val isFront = state.lensFacing == CameraSelector.LENS_FACING_FRONT
        val backLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val discoveredMin = if (!isFront) {
            if (ultraWideLens != null) 0.5f else (backLenses.minOfOrNull { it.minZoomRatio } ?: zs.minZoomRatio)
        } else {
            0.8f
        }
        val discoveredMax = if (!isFront) {
            backLenses.maxOfOrNull { it.maxZoomRatio } ?: zs.maxZoomRatio
        } else {
            zs.maxZoomRatio
        }
        state.minZoom = discoveredMin
        state.maxZoom = discoveredMax
        state.zoom = (zs.zoomRatio * zoomFactor).coerceIn(state.minZoom, state.maxZoom)
        state.zoomChips = ZoomMath.chips(
            minZoom = state.minZoom,
            maxZoom = state.maxZoom,
            teleRatios = state.caps.teleRatios,
            isFront = isFront,
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null && exposure != null && iso != null) {
                frameMeta[timestamp] = longArrayOf(exposure, iso.toLong())
                if (frameMeta.size > 90) {
                    val cutoff = timestamp - 3_000_000_000L
                    frameMeta.keys.removeAll { it < cutoff }
                }
            }
            val aeOff = request.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF
            if (!aeOff && exposure != null && iso != null) {
                meteredShutterNs = exposure
                meteredIso = iso
            }
            lastAperture = result.get(CaptureResult.LENS_APERTURE)
            lastFocalMm = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val activeId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: boundPhysicalId

            backgroundLensManager.syncMainCaptureResult(request, result)
            // Fresh preview frames are reported by TextureView listeners, not by CaptureResult.

            val now = SystemClock.uptimeMillis()
            if (now - lastReadoutMs < 250) return
            lastReadoutMs = now
            val aperture = result.get(CaptureResult.LENS_APERTURE)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val shutterNow = meteredShutterNs
            val isoNow = meteredIso
            mainHandler.post {
                val ratio = activeId?.let { state.caps.lensRatios[it] }
                if (ratio != null) activeLensRatio = ratio
                val lens = when {
                    activeDiscoveredLens?.lensType == LensType.ULTRAWIDE -> LensKind.ULTRAWIDE
                    activeDiscoveredLens?.lensType == LensType.TELEPHOTO -> LensKind.TELEPHOTO
                    activeDiscoveredLens?.lensType == LensType.TELEPHOTO_3X -> LensKind.TELEPHOTO
                    ratio != null -> LensMath.kindForRatio(ratio)
                    state.lensFacing == CameraSelector.LENS_FACING_FRONT -> LensKind.WIDE
                    state.zoom < 0.95f -> LensKind.ULTRAWIDE
                    state.caps.teleRatios.any { state.zoom >= it - 0.05f } -> LensKind.TELEPHOTO
                    else -> LensKind.WIDE
                }
                state.readout = Readout(lens, aperture, exposure, iso, focus)
                if (burst == null) {
                    state.dark = ShotPlanner.isDark(shutterNow, isoNow)
                    state.nightEstimate = ShotPlanner.autoNightSeconds(shutterNow, isoNow, ShotPlanner.motionFor(motionProvider()))
                }
            }
        }
    }

    /** Every frame of the full-resolution stream: bursts, QR codes, highlight statistics, lens haze. */
    private val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            val session = burst
            if (session != null) {
                feedBurst(session, image)
                return@Analyzer
            }
            val panorama = pano
            if (panorama != null) {
                panorama.offer(image)
                return@Analyzer
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastStats > 500) {
                lastStats = now
                clippedFraction = sampleClipped(image)
            }
            if (settings.qrDetection && state.intentMode == IntentMode.NORMAL && now - lastQrScan > 350) {
                lastQrScan = now
                val text = runCatching { qrScanner.scan(image) }.getOrNull()
                mainHandler.post {
                    if (text != null) {
                        lastQrHit = SystemClock.uptimeMillis()
                        state.qrText = text
                    } else if (SystemClock.uptimeMillis() - lastQrHit > 2500) {
                        state.qrText = null
                    }
                }
            }
            if (settings.dirtyLens && now - lastHazeCheck > 2000 && meteredIso <= 400) {
                lastHazeCheck = now
                if (runCatching { LensHaze.looksHazy(image) }.getOrDefault(false)) hazeStrikes++ else hazeStrikes = 0
                if (hazeStrikes >= 3 && now - lastHazeWarning > 30 * 60_000L) {
                    hazeStrikes = 0
                    lastHazeWarning = now
                    mainHandler.post { onLensHazy() }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun sampleClipped(image: ImageProxy): Float {
        val buf = image.planes[0].buffer
        var clipped = 0
        var total = 0
        var i = 0
        val limit = buf.limit()
        while (i < limit) {
            if ((buf.get(i).toInt() and 0xff) >= 250) clipped++
            total++
            i += 97
        }
        return if (total == 0) 0f else clipped.toFloat() / total
    }

    private fun feedBurst(session: BurstSession, image: ImageProxy) {
        val ts = image.imageInfo.timestamp
        var meta = frameMeta[ts]
        // The capture result sometimes arrives a moment after the picture: wait briefly when exposure matters.
        if (meta == null && (session.plan.exposureNs != null || session.wantsHighlight)) {
            var tries = 0
            while (meta == null && tries++ < 10) {
                Thread.sleep(5)
                meta = frameMeta[ts]
            }
        }
        val more = session.offer(image, meta?.get(0) ?: 0L, meta?.get(1)?.toInt() ?: 0)
        if (more && session.wantsHighlight && !highlightRequested) {
            highlightRequested = true
            mainHandler.post { requestHighlightFrame(session) }
        }
    }

    suspend fun start(owner: LifecycleOwner) {
        this.owner = owner
        if (provider == null) provider = awaitProvider()
        rebind()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, mainExecutor)
    }

    fun release() {
        closeCamera2()
        camera2Surface?.release()
        camera2Surface = null
        camera2TextureView?.let { textureView ->
            (textureView.parent as? android.view.ViewGroup)?.removeView(textureView)
        }
        camera2TextureView = null
        try { camera2Thread.quitSafely() } catch (_: Throwable) {}
        zoomTransitionController.cancelTransition()
        backgroundLensManager.release()
        burst?.stop()
        pano?.stop()
        recording?.stop()
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private enum class Setup { PROCESSING, CLASSIC, CLASSIC_SIMPLE, VIDEO, VIDEO_NO_SNAPSHOT, VIDEO_SIMPLE }

    /** Night, Long Exposure, Action Pan and Panorama always need Foco's frames; Photo only if processing is on. */
    private fun wantsProcessing(caps: Capabilities): Boolean = state.kind == CaptureKind.PHOTO && (
        state.photoMode != PhotoMode.PHOTO ||
            settings.processing && !settings.raw && settings.flash != FlashSetting.ON &&
            !(settings.resolution == ResolutionSetting.MP50 && caps.hiResAvailable)
        )

    /** Rebinds only if the new photo mode needs a different camera setup. */
    fun photoModeChanged() {
        val caps = state.caps
        if (wantsProcessing(caps) != state.processing) rebind()
        if (state.photoMode == PhotoMode.PANORAMA) setZoom(1f, fromChip = true)
    }

    /** (Re)binds everything for the current mode/settings. Tries simpler setups if the phone refuses a combination. */
    fun rebind() {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return
        if (recording != null || burst != null || pano != null) return

        if (!isSwitchingLens.get()) {
            zoomTransitionController.cancelTransition()
        }
        zoomAnimator?.cancel()

        val requestedBack = state.lensFacing == CameraSelector.LENS_FACING_BACK
        val currentTarget = targetDiscoveredLens ?: activeDiscoveredLens
        val activeRequiresCamera2 = requestedBack && currentTarget != null && !currentTarget.isPrimaryMain && (
            currentTarget.isHiddenAux ||
            (currentTarget.isIndependentCamera && currentTarget.cameraId != "0" && currentTarget.cameraId != "1") ||
            (currentTarget.lensType == LensType.ULTRAWIDE && currentTarget.cameraId != "0")
        )

        if (activeRequiresCamera2) {
            Log.i(TAG, "[LENS_SWITCH_REBIND] Target lens ${currentTarget.displayName} requires Camera2. Preserving/re-opening Camera2 instead of CameraX.")
            LensDebugLog.log("rebind(): re-opening Camera2 for ${currentTarget.displayName}")
            isSwitchingLens.set(true)
            targetDiscoveredLens = currentTarget
            firstTargetFrameArrived = false
            camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            provider.unbindAll()
            camera = null
            imageCapture = null
            videoCapture = null
            analysis = null
            state.cameraReady = false
            openCamera2(currentTarget)
            return
        } else {
            if (isCamera2Active) {
                Log.i(TAG, "[LENS_SWITCH_REBIND] Closing Camera2 before binding CameraX")
                closeCamera2(keepTexture = false)
            }
        }

        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        provider.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        analysis = null
        state.cameraReady = false
        state.qrText = null

        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val hasBack = runCatching { provider.hasCamera(back) }.getOrDefault(false)
        val hasFront = runCatching { provider.hasCamera(front) }.getOrDefault(false)
        if (!hasBack && !hasFront) {
            state.message = "No camera found"
            completeLensSwitch(activeDiscoveredLens)
            return
        }
        if (state.lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack) state.lensFacing = CameraSelector.LENS_FACING_FRONT
        if (state.lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront) state.lensFacing = CameraSelector.LENS_FACING_BACK
        val isBack = state.lensFacing == CameraSelector.LENS_FACING_BACK
        val baseSelector = if (isBack) back else front

        val info = provider.getCameraInfo(baseSelector)
        val caps = probe(info, isBack).copy(hasFront = hasFront && hasBack)
        state.caps = caps

        val targetLens = targetDiscoveredLens ?: activeDiscoveredLens
        val physicalId: String? = when {
            !isBack -> null
            state.kind == CaptureKind.PHOTO && settings.macro && caps.macroAvailable -> caps.ultrawideId
            settings.manualLens && caps.manualLensAvailable && state.manualLensId != null -> state.manualLensId
            targetLens != null && targetLens.facing == CameraCharacteristics.LENS_FACING_BACK -> {
                if (targetLens.isLogicalMultiCamera && targetLens.physicalCameraId != null && targetLens.supportsPhysicalStream) {
                    targetLens.physicalCameraId
                } else if (targetLens.physicalCameraId != null && targetLens.physicalCameraId in caps.lensRatios) {
                    targetLens.physicalCameraId
                } else null
            }
            else -> null
        }

        val targetCamId: String? = when {
            !isBack -> null
            targetLens != null && !targetLens.isPrimaryMain && targetLens.facing == CameraCharacteristics.LENS_FACING_BACK && targetLens.isIndependentCamera && targetLens.cameraId != "0" -> {
                targetLens.cameraId
            }
            else -> null
        }

        val strategy = if (targetLens != null) {
            CameraDiscovery.resolveSwitchStrategy(activeDiscoveredLens, targetLens)
        } else {
            LensSwitchStrategy.INDEPENDENT_DEVICE
        }

        Log.i(
            TAG,
            "[LENS_SWITCH_REBIND] Rebinding with targetLens=${targetLens?.displayName}, cameraId=${targetLens?.cameraId}, physicalId=$physicalId, targetCamId=$targetCamId, strategy=$strategy, manualLens=${settings.manualLens}"
        )

        val setups = when {
            state.kind == CaptureKind.VIDEO -> listOf(Setup.VIDEO, Setup.VIDEO_NO_SNAPSHOT, Setup.VIDEO_SIMPLE)
            wantsProcessing(caps) -> listOf(Setup.PROCESSING, Setup.CLASSIC, Setup.CLASSIC_SIMPLE)
            else -> listOf(Setup.CLASSIC, Setup.CLASSIC_SIMPLE)
        }

        var bound: Camera? = null
        var usedPhysical = physicalId
        var usedSetup = setups.first()
        val selectors = buildList {
            if (targetCamId != null) {
                val idSelector = CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        cameras.filter {
                            runCatching { Camera2CameraInfo.from(it).cameraId == targetCamId }.getOrDefault(false)
                        }
                    }
                    .build()
                if (runCatching { provider.hasCamera(idSelector) }.getOrDefault(false)) {
                    add(idSelector)
                }
            }
            if (physicalId != null) {
                add(
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .setPhysicalCameraId(physicalId)
                        .build()
                )
            }
            add(baseSelector)
        }

        loop@ for (selector in selectors) {
            for (setup in setups) {
                val cases = buildUseCases(view, caps, setup, usedPhysical)
                bound = try {
                    provider.bindToLifecycle(owner, selector, *cases.toTypedArray())
                } catch (e: Exception) {
                    Log.w(TAG, "[LENS_SWITCH] bind failed ($setup, selector=$selector)", e)
                    provider.unbindAll()
                    null
                }
                if (bound != null && setup == Setup.PROCESSING) {
                    // Only worth it with (nearly) full-resolution frames.
                    val size = analysis?.resolutionInfo?.resolution
                    if (size == null || size.width.toLong() * size.height < 8_000_000L) {
                        Log.i(TAG, "full-resolution frames not available ($size): using the camera's own photos")
                        provider.unbindAll()
                        bound = null
                    }
                }
                if (bound != null) {
                    usedSetup = setup
                    if (setup == Setup.CLASSIC_SIMPLE || setup == Setup.VIDEO_SIMPLE) {
                        state.message = "Some of these options can't be combined on this phone"
                    }
                    if (selector == baseSelector && physicalId != null && targetCamId == null) {
                        usedPhysical = null
                    }
                    break@loop
                }
            }
            if (selector != baseSelector && bound == null) {
                Log.w(TAG, "[LENS_SWITCH] Failed to bind auxiliary/physical selector $selector")
            }
        }
        val cam = bound ?: run {
            val failedLens = targetDiscoveredLens
            targetDiscoveredLens = null
            completeLensSwitch(activeDiscoveredLens)
            val msg = if (failedLens != null) "Failed to activate ${failedLens.displayName}" else "Camera couldn't be started"
            state.message = msg
            Log.e(TAG, "[LENS_SWITCH_FAILURE] $msg (active: ${activeDiscoveredLens?.displayName})")
            LensDebugLog.log("CameraX rebind failed for ${failedLens?.displayName ?: "current lens"}")
            return
        }
        camera = cam
        previewView?.post { attachCameraXFrameListener() }
        state.processing = usedSetup == Setup.PROCESSING
        boundPhysicalId = usedPhysical
        val c2Info = Camera2CameraInfo.from(cam.cameraInfo)
        boundCameraId = c2Info.cameraId

        Log.i(
            TAG,
            "[LENS_SWITCH_SESSION] Camera open and session configured. CameraX CameraInfo ID=$boundCameraId, physicalId=$boundPhysicalId, supported zoom=[${cam.cameraInfo.zoomState.value?.minZoomRatio}, ${cam.cameraInfo.zoomState.value?.maxZoomRatio}]"
        )

        zoomFactor = when {
            usedPhysical != null -> caps.lensRatios[usedPhysical] ?: 1f
            targetCamId != null && boundCameraId == targetCamId -> targetLens?.baseZoomRatio ?: 1f
            else -> 1f
        }

        if (usedPhysical == null && targetCamId == null) {
            if (settings.manualLens) state.manualLensId = null
        } else if (!settings.macro && settings.manualLens) {
            state.manualLensId = usedPhysical ?: targetCamId
        }

        val currentFacing = state.lensFacing
        val matchingLenses = inventory.lenses.filter {
            if (currentFacing == CameraSelector.LENS_FACING_FRONT) it.facing == CameraCharacteristics.LENS_FACING_FRONT
            else it.facing == CameraCharacteristics.LENS_FACING_BACK
        }
        val newlyBoundLens = if (usedPhysical != null) {
            matchingLenses.firstOrNull { it.physicalCameraId == usedPhysical || it.cameraId == usedPhysical }
        } else if (targetCamId != null && boundCameraId == targetCamId) {
            matchingLenses.firstOrNull { it.cameraId == targetCamId }
        } else {
            matchingLenses.firstOrNull { it.cameraId == boundCameraId && !it.isPhysical }
                ?: matchingLenses.firstOrNull { it.isPrimaryMain }
                ?: matchingLenses.firstOrNull { it.lensType == LensType.WIDE }
                ?: matchingLenses.firstOrNull()
        }

        // Clamp desiredZoom to what the actually bound camera supports
        val zs = cam.cameraInfo.zoomState.value
        if (zs != null) {
            val minZoom = zs.minZoomRatio * zoomFactor
            val maxZoom = zs.maxZoomRatio * zoomFactor
            desiredZoom = desiredZoom.coerceIn(minZoom, maxZoom)
            state.zoom = desiredZoom
        }

        cam.cameraInfo.zoomState.observe(owner, zoomObserver)
        applyFlash()
        applyPro()
        applyZoom(desiredZoom)
        state.cameraReady = true

        if (targetLens != null && targetLens.lensType == LensType.ULTRAWIDE && newlyBoundLens?.lensType != LensType.ULTRAWIDE) {
            Log.w(TAG, "[LENS_SWITCH] Ultra-wide selector requested but CameraX bound main camera. Resetting target lens.")
            targetDiscoveredLens = null
            activeDiscoveredLens = newlyBoundLens
            completeLensSwitch(newlyBoundLens)
        } else {
            activeDiscoveredLens = newlyBoundLens
            _activePreviewSource.value = if (newlyBoundLens?.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
            if (targetDiscoveredLens != null) {
                // CameraX bind completion is not a preview frame. Keep the switch in flight until
                // TextureView.onSurfaceTextureUpdated confirms a fresh, displayable buffer.
                LensDebugLog.log("CameraX bound; waiting for first fresh TextureView frame")
            } else {
                completeLensSwitch(newlyBoundLens)
            }
            LensDebugLog.log("CameraX rebind complete: active=${newlyBoundLens?.displayName}, cameraId=$boundCameraId, zoom=${state.zoom}")
        }

        if (currentFacing == CameraSelector.LENS_FACING_BACK) {
            backgroundLensPrepChanged()
        }
    }

    private fun buildUseCases(view: PreviewView, caps: Capabilities, setup: Setup, physicalCameraId: String? = boundPhysicalId): List<UseCase> {
        val isVideo = state.kind == CaptureKind.VIDEO
        val ratio = if (isVideo || settings.ratio == RatioSetting.WIDE_9_16) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val aspect = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
        if (physicalCameraId != null) {
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
        }
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
        val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val cases = mutableListOf<UseCase>(preview)
        imageCapture = null
        videoCapture = null
        analysis = null
        state.snapshotAvailable = false

        when (setup) {
            Setup.PROCESSING -> {
                // Full-resolution YUV frames (4:3 = whole sensor) that Foco merges itself.
                val fullBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(targetRotation)
                if (physicalCameraId != null) {
                    Camera2Interop.Extender(fullBuilder).setPhysicalCameraId(physicalCameraId)
                }
                val full = fullBuilder.build()
                full.setAnalyzer(analysisExecutor, analyzer)
                analysis = full
                cases += full
            }
            Setup.CLASSIC, Setup.CLASSIC_SIMPLE -> {
                val simple = setup == Setup.CLASSIC_SIMPLE
                val wantHiRes = !simple && settings.resolution == ResolutionSetting.MP50 &&
                    caps.hiResAvailable && ratio == AspectRatio.RATIO_4_3
                photoFormat = when {
                    !simple && settings.raw && caps.rawSupported -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    !simple && settings.ultraHdr && caps.ultraHdrSupported && !wantHiRes -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    else -> ImageCapture.OUTPUT_FORMAT_JPEG
                }
                val selector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect).apply {
                    if (wantHiRes) {
                        setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        setResolutionStrategy(
                            ResolutionStrategy(Size(caps.hiResWidth, caps.hiResHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                    } else {
                        setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    }
                }.build()
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(selector)
                    .setOutputFormat(photoFormat)
                    .setTargetRotation(targetRotation)
                    .apply { screenFlash?.let { setScreenFlash(it) } }
                    .setFlashMode(photoFlashMode(caps))
                if (physicalCameraId != null) {
                    Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalCameraId)
                }
                val capture = captureBuilder.build()
                imageCapture = capture
                cases += capture
                if (!simple && settings.qrDetection && state.intentMode == IntentMode.NORMAL) {
                    val smallBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    if (physicalCameraId != null) {
                        Camera2Interop.Extender(smallBuilder).setPhysicalCameraId(physicalCameraId)
                    }
                    val small = smallBuilder.build()
                    small.setAnalyzer(analysisExecutor, analyzer)
                    analysis = small
                    cases += small
                }
            }
            Setup.VIDEO, Setup.VIDEO_NO_SNAPSHOT, Setup.VIDEO_SIMPLE -> {
                val simple = setup == Setup.VIDEO_SIMPLE
                val quality = if (settings.videoResolution == VideoResolution.UHD && caps.uhdSupported && !simple) Quality.UHD else Quality.FHD
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .setAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                val builder = VideoCapture.Builder(recorder)
                    .setTargetRotation(targetRotation)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                if (!simple) {
                    if (settings.stabilization && caps.stabilizationSupported) builder.setVideoStabilizationEnabled(true)
                    when {
                        settings.frameRate == FrameRate.FPS60 && caps.fps60Supported -> builder.setTargetFrameRate(Range(60, 60))
                        settings.frameRate == FrameRate.AUTO && caps.autoFpsLower > 0 -> builder.setTargetFrameRate(Range(caps.autoFpsLower, 30))
                        else -> Unit
                    }
                    if (settings.hdrVideo && caps.hdrVideoSupported) builder.setDynamicRange(DynamicRange.HLG_10_BIT)
                }
                val video = builder.build()
                videoCapture = video
                cases += video
                if (setup == Setup.VIDEO && state.intentMode == IntentMode.NORMAL) {
                    val snapshotBuilder = ImageCapture.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build(),
                        )
                        .setTargetRotation(targetRotation)
                    if (physicalCameraId != null) {
                        Camera2Interop.Extender(snapshotBuilder).setPhysicalCameraId(physicalCameraId)
                    }
                    val snapshot = snapshotBuilder.build()
                    photoFormat = ImageCapture.OUTPUT_FORMAT_JPEG
                    imageCapture = snapshot
                    cases += snapshot
                }
                state.snapshotAvailable = imageCapture != null
            }
        }
        return cases
    }

    /** Reads what this camera can do. Uses Camera2 characteristics directly so nothing is guessed. */
    private fun probe(info: CameraInfo, isBack: Boolean): Capabilities {
        val c2 = Camera2CameraInfo.from(info)
        fun <T : Any> ch(key: CameraCharacteristics.Key<T>): T? = runCatching { c2.getCameraCharacteristic(key) }.getOrNull()

        val available = ch(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in available
        val isoRange = ch(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = ch(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = ch(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = ch(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val toneModes = ch(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val maxCurvePoints = ch(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val cct = Build.VERSION.SDK_INT >= 36 &&
            ch(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true
        val hiRes = ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getHighResolutionOutputSizes(ImageFormat.JPEG)
            ?.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            ?.maxByOrNull { it.width.toLong() * it.height }
        val formats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }.getOrDefault(emptySet())
        val exposure = info.exposureState
        val video = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
        val sdrQualities = runCatching { video?.getSupportedQualities(DynamicRange.SDR) }.getOrNull().orEmpty()
        val hdrVideo = runCatching { video?.supportedDynamicRanges?.contains(DynamicRange.HLG_10_BIT) == true }.getOrDefault(false)
        val stabilization = runCatching { video?.isStabilizationSupported == true }.getOrDefault(false)
        val fpsRanges = runCatching { info.supportedFrameRateRanges }.getOrDefault(emptySet())
        val fps60 = fpsRanges.any { it.lower == 60 && it.upper == 60 }
        val autoLower = fpsRanges.filter { it.upper == 30 && it.lower in 15..29 }.map { it.lower }
            .let { lows -> if (24 in lows) 24 else lows.minOrNull() ?: 0 }

        val logicalFocal = ch(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val logicalSize = ch(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val logicalEq = if (logicalFocal != null && logicalSize != null) {
            LensSpec("logical", logicalFocal, logicalSize.width, logicalSize.height, null, 0f).equivalentFocalMm
        } else {
            null
        }

        // Advanced Lens Detection ported from source ZIP
        if (inventory.lenses.isEmpty()) {
            inventory = CameraDiscovery.discover(cameraManager)
        }
        val discoveredBackLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }

        // Physical lenses (only the back camera on Pixels has several).
        var ratios = emptyMap<String, Float>()
        var lenses = emptyList<LensSpec>()
        if (isBack) {
            lenses = LensMath.distinct(probeLenses(c2.cameraId))
            val baseRatios = LensMath.ratios(lenses, logicalEq).toMutableMap()
            // Enhance with discovered physical lenses (including hidden aux cameras)
            for (lens in discoveredBackLenses) {
                val key = lens.physicalCameraId ?: lens.cameraId
                if (!baseRatios.containsKey(key)) {
                    baseRatios[key] = lens.baseZoomRatio
                }
            }
            ratios = baseRatios
        }
        val cameraXPhysical = runCatching { info.physicalCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet() }
            .getOrDefault(emptySet())
        val selectable = if (cameraXPhysical.isNotEmpty()) ratios.filterKeys { it in cameraXPhysical } else ratios
        val ultrawide = discoveredBackLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }?.let { it.physicalCameraId ?: it.cameraId }
            ?: selectable.filter { it.value < 0.8f }.minByOrNull { it.value }?.key
        val ultrawideFocus = lenses.firstOrNull { it.id == ultrawide }?.minFocusDiopters ?: 0f
        val hasMacro = discoveredBackLenses.any { it.lensType == LensType.MACRO } || (ultrawide != null && ultrawideFocus > minFocus * 1.5f)
        val evRange = exposure.exposureCompensationRange
        val evStep = exposure.exposureCompensationStep

        return Capabilities(
            hasFlash = info.hasFlashUnit(),
            rawSupported = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats,
            ultraHdrSupported = ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats,
            hiResWidth = hiRes?.width ?: 0,
            hiResHeight = hiRes?.height ?: 0,
            lensRatios = ratios,
            ultrawideId = ultrawide,
            macroAvailable = hasMacro,
            manualLensAvailable = selectable.size > 1 || discoveredBackLenses.size > 1,
            uhdSupported = Quality.UHD in sdrQualities,
            fps60Supported = fps60,
            stabilizationSupported = stabilization,
            hdrVideoSupported = hdrVideo,
            evMin = if (exposure.isExposureCompensationSupported) evRange.lower else 0,
            evMax = if (exposure.isExposureCompensationSupported) evRange.upper else 0,
            evStep = if (evStep.denominator != 0) evStep.toFloat() else 1f / 3f,
            manualSensor = manualSensor && isoRange != null && shutterRange != null,
            isoMin = isoRange?.lower ?: 50,
            isoMax = isoRange?.upper ?: 3200,
            shutterMinNs = shutterRange?.lower ?: 100_000L,
            shutterMaxNs = shutterRange?.upper ?: 500_000_000L,
            manualFocus = manualSensor && minFocus > 0f,
            minFocusDiopters = minFocus,
            cctSupported = cct,
            awbModes = awb,
            tonemapCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneModes && maxCurvePoints >= 2,
            shadowsCurvePoints = maxCurvePoints.coerceIn(2, 33),
            equivalentFocalMm = logicalEq ?: 0f,
            autoFpsLower = autoLower,
            focalMm = logicalFocal ?: 0f,
            sensorWidthMm = logicalSize?.width ?: 0f,
        )
    }

    private fun probeLenses(logicalId: String): List<LensSpec> = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        val logical = manager.getCameraCharacteristics(logicalId)
        logical.physicalCameraIds.mapNotNull { id ->
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching null
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
                LensSpec(
                    id = id,
                    focalLengthMm = focal,
                    sensorWidthMm = size.width,
                    sensorHeightMm = size.height,
                    aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                    minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun photoFlashMode(caps: Capabilities = state.caps): Int = when {
        settings.flash != FlashSetting.ON -> ImageCapture.FLASH_MODE_OFF
        state.lensFacing == CameraSelector.LENS_FACING_FRONT ->
            if (screenFlash != null) ImageCapture.FLASH_MODE_SCREEN else ImageCapture.FLASH_MODE_OFF
        caps.hasFlash -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    fun applyFlash() {
        if (isCamera2Active && camera2Device != null && camera2Session != null) {
            val req = camera2RequestBuilder ?: return
            val flashMode = when {
                settings.flash == FlashSetting.ON -> CameraMetadata.FLASH_MODE_SINGLE
                state.kind == CaptureKind.VIDEO && settings.torch -> CameraMetadata.FLASH_MODE_TORCH
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            req.set(CaptureRequest.FLASH_MODE, flashMode)
            try {
                camera2Session?.setRepeatingRequest(req.build(), null, camera2Handler)
            } catch (_: Throwable) {}
            return
        }
        if (state.kind == CaptureKind.PHOTO) runCatching { imageCapture?.flashMode = photoFlashMode() }
        val torch = state.kind == CaptureKind.VIDEO && settings.torch && state.caps.hasFlash
        camera?.cameraControl?.enableTorch(torch)
    }

    /** Sends the Pro values (and, during a burst, the burst's exposure) to the camera. */
    fun applyPro() {
        if (isCamera2Active && camera2Device != null && camera2Session != null) {
            val req = camera2RequestBuilder ?: return
            applyCamera2Pro(req)
            try {
                camera2Session?.setRepeatingRequest(req.build(), null, camera2Handler)
            } catch (_: Throwable) {}
            return
        }
        val cam = camera ?: return
        val caps = state.caps
        val pro = state.pro
        val options = CaptureRequestOptions.Builder()
        val override = burstOverride
        if (override != null) {
            if (override.exposureNs != null && override.iso != null) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, override.exposureNs)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, override.iso)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(override.exposureNs, override.frameNs))
            } else {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        } else if (caps.manualSensor && (pro.shutterNs != null || pro.iso != null)) {
            val (shutter, iso) = ExposureMath.complete(
                pro.shutterNs, pro.iso, meteredShutterNs, meteredIso,
                caps.shutterMinNs..caps.shutterMaxNs, caps.isoMin..caps.isoMax,
            )
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
        }
        val focus = pro.focusDiopters
        if (caps.manualFocus && focus != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        val kelvin = pro.wbKelvin
        if (kelvin != null) {
            if (caps.cctSupported && Build.VERSION.SDK_INT >= 36) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
                options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, kelvin)
            } else if (caps.awbModes.size > 1) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WhiteBalanceMath.presetFor(kelvin, caps.awbModes))
            }
        }
        // With Foco's processing the Shadows control is applied to the photo itself; otherwise via the camera.
        if (!state.processing && caps.tonemapCurve && abs(pro.shadows) > 0.01f) {
            val curve = ToneCurve.shadows(pro.shadows, caps.shadowsCurvePoints)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        if (caps.evMax > caps.evMin && override == null) {
            cam.cameraControl.setExposureCompensationIndex(pro.evIndex.coerceIn(caps.evMin, caps.evMax))
        }
    }

    // ------------------------------------------------------------------ zoom & focus

    // ------------------------------------------------------------------ zoom & focus

    /** @param zoom zoom as shown to the user (0.5 = ultrawide). Chip taps glide smoothly with ported zoom transition algorithms. */
    fun setZoom(zoom: Float, fromChip: Boolean) {
        if (zoom < 0.95f || fromChip) {
            LensDebugLog.log("chip tap / setZoom args: zoom=$zoom, fromChip=$fromChip")
        }
        desiredZoom = zoom
        val isFront = state.lensFacing == CameraSelector.LENS_FACING_FRONT
        val backLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainWideLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            ?: backLenses.firstOrNull()

        val minAllowedZoom = if (isFront) 0.8f else (ultraWideLens?.minZoomRatio ?: 0.5f)
        val maxAllowedZoom = if (isFront) 1.0f else maxOf(state.maxZoom, backLenses.maxOfOrNull { it.maxZoomRatio } ?: 20.0f)
        val clampedZoom = zoom.coerceIn(minAllowedZoom, maxAllowedZoom)

        val switchPoint = settings.switchPointMm
        val currentLens = activeDiscoveredLens ?: mainWideLens
        val targetType = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = currentLens?.lensType ?: LensType.WIDE,
            targetZoom = clampedZoom,
            hasUltraWide = ultraWideLens != null,
            hasTelephoto2x = backLenses.any { it.lensType == LensType.TELEPHOTO },
            hasTelephoto3x = backLenses.any { it.lensType == LensType.TELEPHOTO_3X },
            isPresetTap = fromChip,
            switchPointMm = switchPoint,
            isContinuousTransition = !fromChip
        )
        val targetLens = when (targetType) {
            LensType.ULTRAWIDE -> ultraWideLens ?: mainWideLens ?: currentLens
            LensType.TELEPHOTO -> backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO } ?: mainWideLens ?: currentLens
            LensType.TELEPHOTO_3X -> backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X } ?: mainWideLens ?: currentLens
            else -> mainWideLens ?: currentLens
        }

        if (fromChip && settings.zoomTransitionAnimation) {
            val duration = if (state.kind == CaptureKind.VIDEO || isRecording) {
                ZoomTransitionController.VIDEO_TRANSITION_DURATION_MS
            } else {
                ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS
            }

            zoomTransitionController.startTransition(
                fromZoom = state.zoom,
                targetZoom = clampedZoom,
                targetLens = targetLens,
                availableLenses = inventory.lenses,
                currentLens = currentLens,
                switchPointMm = switchPoint,
                scope = engineScope,
                durationMs = duration,
                skipHoldAtSwitchPoint = ZoomTransitionController.isHalfXToTelephotoPresetTransition(state.zoom, clampedZoom),
                onZoomUpdate = { z ->
                    state.zoom = z
                },
                onComplete = { finalZoom, finalLens ->
                    state.zoom = finalZoom
                    desiredZoom = finalZoom
                    pendingZoomUpdateTarget = finalZoom
                    val finalTarget = finalLens ?: targetLens
                    if (isSwitchingLens.get()) {
                        // The physical frame callback owns switch completion. Do not overwrite a
                        // newer drag/tap request that was coalesced after this transition started.
                        if (pendingZoomWhileSwitching == null) pendingZoomWhileSwitching = finalZoom
                        if (pendingLensWhileSwitching == null && finalTarget != null &&
                            targetDiscoveredLens?.id != finalTarget.id
                        ) {
                            pendingLensWhileSwitching = finalTarget
                        }
                    } else if (finalTarget != null && finalTarget.id != activeDiscoveredLens?.id) {
                        selectDiscoveredLens(finalTarget, finalZoom, isContinuous = false)
                    } else {
                        scheduleZoomPreviewUpdate(immediate = true, explicitZoom = finalZoom)
                    }
                }
            )
        } else {
            zoomTransitionController.cancelTransition()
            if (targetLens != null && (isSwitchingLens.get() ||
                targetLens.lensType != currentLens?.lensType ||
                targetLens.physicalCameraId != boundPhysicalId)
            ) {
                selectDiscoveredLens(targetLens, clampedZoom, isContinuous = !fromChip)
            } else {
                applyZoomInternal(clampedZoom, isContinuous = !fromChip)
            }
        }
    }

    fun applyZoomInternal(zoom: Float, isContinuous: Boolean) {
        state.zoom = zoom
        val handoffActive = zoomTransitionController.previewOverlapState.value.isOverlapping
        val ultraWideIsActive = _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE
        if (backgroundLensManager.isStandbyReady()) {
            val standbyZoom = if (handoffActive) zoomTransitionController.currentInterpolatedZoom.value else zoom
            backgroundLensManager.updateZoom(standbyZoom, handoffActive, ultraWideIsActive)
        }
        if (ultraWideIsActive && !handoffActive && !isCamera2Active && backgroundLensManager.isStandbyReady()) {
            return
        }
        if (isCamera2Active && camera2Device != null && camera2Session != null) {
            val req = camera2RequestBuilder ?: return
            applyCamera2Zoom(zoom, req)
            try {
                camera2Session?.setRepeatingRequest(req.build(), null, camera2Handler)
            } catch (_: Throwable) {}
            return
        }
        val cam = camera ?: run {
            if (isSwitchingLens.get()) {
                pendingZoomWhileSwitching = zoom
                pendingIsContinuousTransitionWhileSwitching = isContinuous
                pendingZoomUpdateTarget = zoom
                Log.d(TAG, "[LENS_SWITCH_COALESCE] zoom=$zoom saved while camera is between sessions")
            }
            return
        }
        val zs = cam.cameraInfo.zoomState.value
        val raw = zoom / zoomFactor
        val clamped = if (zs != null) raw.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else raw
        cam.cameraControl.setZoomRatio(clamped)
    }

    fun selectDiscoveredLens(lens: LensInfo, targetZoom: Float, isContinuous: Boolean) {
        val nowMs = SystemClock.uptimeMillis()
        if (!isSwitchingLens.compareAndSet(false, true)) {
            if (nowMs - lastLensSwitchStartTimeMs > 1800L) {
                Log.w(TAG, "[LENS_SWITCH_COALESCE] Previous switch timed out (>1800ms); accepting latest target ${lens.displayName}")
                lensSwitchGeneration.incrementAndGet()
                isSwitchingLens.set(true)
            } else {
                desiredZoom = targetZoom
                state.zoom = targetZoom
                pendingLensWhileSwitching = lens
                pendingZoomWhileSwitching = targetZoom
                pendingIsContinuousTransitionWhileSwitching = isContinuous
                Log.d(TAG, "[LENS_SWITCH_COALESCE] pending lens=${lens.displayName} zoom=$targetZoom continuous=$isContinuous")
                if (camera != null) scheduleZoomPreviewUpdate(immediate = isContinuous, explicitZoom = targetZoom)
                else backgroundLensManager.updateZoom(targetZoom, zoomTransitionController.previewOverlapState.value.isOverlapping,
                    _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE)
                return
            }
        }
        lastLensSwitchStartTimeMs = nowMs
        desiredZoom = targetZoom
        val currentLens = activeDiscoveredLens
        val isDifferentLens = (currentLens == null) ||
            (currentLens.id != lens.id) ||
            (currentLens.lensType != lens.lensType) ||
            (lens.physicalCameraId != boundPhysicalId) ||
            (lens.isIndependentCamera && lens.cameraId != boundCameraId)

        val officialIds = try { cameraManager?.cameraIdList?.toSet() ?: emptySet() } catch (_: Throwable) { emptySet() }
        val inOfficial = lens.cameraId in officialIds
        LensDebugLog.log("selectDiscoveredLens: ${lens.displayName} (camId=${lens.cameraId}, physId=${lens.physicalCameraId}, type=${lens.lensType}, isHidden=${lens.isHiddenAux}, isIndep=${lens.isIndependentCamera}, inOfficialList=$inOfficial, targetZoom=$targetZoom)")

        if (lens.lensType == LensType.ULTRAWIDE || targetZoom < 0.95f) {
            val chars = try { cameraManager?.getCameraCharacteristics(lens.cameraId) } catch (_: Throwable) { null }
            val canUseLogical = CameraDiscovery.canUseLogicalZoomForLens(lens.cameraId, lens, targetZoom, chars)
            val strat = if (canUseLogical) "LOGICAL_ZOOM" else CameraDiscovery.resolveSwitchStrategy(currentLens, lens).name
            Log.i("LensDiag", "[0.5x REQUEST] selectDiscoveredLens: ${lens.displayName} (cameraId=${lens.cameraId}, physId=${lens.physicalCameraId}), Strategy: $strat, zoom=$targetZoom")
        }

        if (isDifferentLens && !isRecording && !isBursting) {
            val chars = try { cameraManager?.getCameraCharacteristics(lens.cameraId) } catch (_: Throwable) { null }
            val supportsLogicalZoom = CameraDiscovery.canUseLogicalZoomForLens(lens.cameraId, lens, targetZoom, chars)
            // A logical multi-camera can only service the handoff if THAT logical device is the
            // device currently bound/open. Merely having logical-multicam characteristics on the
            // target camera is not sufficient when the active stream is an independent aux ID.
            val activeDeviceCameraId = when {
                isCamera2Active -> camera2Device?.id
                camera != null -> boundCameraId
                else -> null
            }
            val canUseLogical = supportsLogicalZoom && activeDeviceCameraId == lens.cameraId
            val strategy = CameraDiscovery.resolveSwitchStrategy(currentLens, lens)

            LensDebugLog.log(
                "lens route decision: target=${lens.displayName}, targetCameraId=${lens.cameraId}, activeDeviceCameraId=$activeDeviceCameraId, logicalSupported=$supportsLogicalZoom, useLogical=$canUseLogical"
            )

            if (canUseLogical) {
                Log.i(TAG, "[LENS_SWITCH] LOGICAL_ZOOM strategy active on currently bound device ${lens.cameraId} for ${lens.displayName} at zoom $targetZoom")
                activeDiscoveredLens = lens
                applyZoomInternal(targetZoom, isContinuous)
                completeLensSwitch(lens, isContinuous)
            } else {
                Log.i(TAG, "[LENS_SWITCH] Physical device handoff required for ${lens.displayName} (strategy=$strategy, activeCameraId=$activeDeviceCameraId, manualLens=${settings.manualLens})")
                switchCameraToLens(lens, targetZoom)
            }
        } else {
            applyZoomInternal(targetZoom, isContinuous)
            completeLensSwitch(currentLens, isContinuous)
        }
    }

    fun switchCameraToLens(targetLens: LensInfo, targetZoom: Float) {
        val currentLens = activeDiscoveredLens
        val strategy = CameraDiscovery.resolveSwitchStrategy(currentLens, targetLens)
        targetDiscoveredLens = targetLens
        desiredZoom = targetZoom
        lensSwitchRequestedAtMs = SystemClock.uptimeMillis()
        lastLensSwitchStartTimeMs = lensSwitchRequestedAtMs
        isSwitchingLens.set(true)
        firstTargetFrameArrived = false
        val switchGen = lensSwitchGeneration.incrementAndGet()

        val officialIds = try { cameraManager?.cameraIdList?.toSet() ?: emptySet() } catch (_: Throwable) { emptySet() }
        val inOfficial = targetLens.cameraId in officialIds
        LensDebugLog.log("switchCameraToLens: ${targetLens.displayName} (camId=${targetLens.cameraId}, physId=${targetLens.physicalCameraId}, type=${targetLens.lensType}, isHidden=${targetLens.isHiddenAux}, isIndep=${targetLens.isIndependentCamera}, inOfficialList=$inOfficial, targetZoom=$targetZoom)")

        Log.i(
            TAG,
            "[LENS_SWITCH_REQUEST] Requested lens: ${targetLens.displayName} (cameraId=${targetLens.cameraId}, physicalId=${targetLens.physicalCameraId}, type=${targetLens.lensType}) from current: ${currentLens?.displayName} via strategy: $strategy"
        )

        val isHiddenAux = targetLens.isHiddenAux || (targetLens.isIndependentCamera && targetLens.cameraId != "0" && targetLens.cameraId != "1")
        val isUw = targetLens.lensType == LensType.ULTRAWIDE
        val isPrimaryMainCameraX = targetLens.isPrimaryMain ||
            (targetLens.lensType == LensType.WIDE && !targetLens.isHiddenAux && !targetLens.isZoomPreset)

        val standbyLens = backgroundLensManager.getStandbyLens()
        val standbyReady = settings.backgroundLensPreparation && settings.instantLensSwitching &&
            backgroundLensManager.isStandbyReady() && standbyLens?.cameraId == targetLens.cameraId
        if (!isRecording && !isBursting && camera != null && !isCamera2Active && standbyReady && isUw) {
            state.zoom = targetZoom
            backgroundLensManager.updateZoom(targetZoom, handoffActive = false, ultraWideIsActive = true)
            Log.i(TAG, "[STANDBY_READY] visual handoff to ${targetLens.displayName}; no unbind/open/close/session creation")
            LensDebugLog.log("prewarmed Ultra-Wide TextureView selected without CameraX unbind")
            return
        }
        val returningToMainFromStandby = !isRecording && !isBursting && camera != null && !isCamera2Active &&
            settings.instantLensSwitching && backgroundLensManager.isStandbyReady() &&
            _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE &&
            (targetLens.isPrimaryMain || (targetLens.lensType == LensType.WIDE && !targetLens.isZoomPreset))
        if (returningToMainFromStandby) {
            state.zoom = targetZoom
            val zs = camera?.cameraInfo?.zoomState?.value
            val raw = targetZoom / zoomFactor
            val clamped = if (zs != null) raw.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else raw
            camera?.cameraControl?.setZoomRatio(clamped)
            backgroundLensManager.updateZoom(targetZoom, handoffActive = false, ultraWideIsActive = false)
            Log.i(TAG, "[STANDBY_READY] visual handoff back to Main at ${targetZoom}x; no unbind/open/close/session creation")
            return
        }

        val cameraXHasCamera = runCatching {
            val filter = CameraSelector.Builder().addCameraFilter { list ->
                list.filter { runCatching { Camera2CameraInfo.from(it).cameraId == targetLens.cameraId }.getOrDefault(false) }
            }.build()
            provider?.hasCamera(filter) == true
        }.getOrDefault(false)

        if (isUw || targetZoom < 0.95f) {
            val path = if (isHiddenAux || !cameraXHasCamera || (isUw && targetLens.cameraId != "0")) "Camera2" else "CameraX physical"
            Log.i("LensDiag", "[0.5x REQUEST] switchCameraToLens: ${targetLens.displayName}, Strategy: $path (isHiddenAux=$isHiddenAux, cameraXHasCamera=$cameraXHasCamera)")
        }

        // For hidden auxiliary lenses (e.g. vendor aux camera ID 2, 3, etc.) or when CameraX doesn't have the camera ID,
        // or for Ultra-Wide when on a separate camera ID:
        // Use Camera2 API directly!
        if (!isPrimaryMainCameraX && (isHiddenAux || !cameraXHasCamera || (isUw && targetLens.cameraId != "0"))) {
            // Reaching this branch means the CameraX-main + standby preview fast path was not
            // eligible. Release the auxiliary session before sequentially opening this same ID.
            Log.i(TAG, "[STANDBY_READY] fast path unavailable for target camera=${targetLens.cameraId}; using sequential fallback")
            backgroundLensManager.release()
            Log.i(TAG, "[LENS_SWITCH_CAM2] Activating Camera2 API directly for hidden/auxiliary lens ${targetLens.displayName} (cameraId=${targetLens.cameraId})")
            val cam = camera
            if (cam != null) {
                val closedDeferred = CompletableDeferred<Unit>()
                val observer = object : Observer<CameraState> {
                    override fun onChanged(state: CameraState) {
                        if (state.type == CameraState.Type.CLOSED) {
                            cam.cameraInfo.cameraState.removeObserver(this)
                            closedDeferred.complete(Unit)
                        }
                    }
                }
                cam.cameraInfo.cameraState.observeForever(observer)
                cam.cameraInfo.zoomState.removeObserver(zoomObserver)
                LensDebugLog.log("unbindAll called")
                provider?.unbindAll()
                camera = null
                imageCapture = null
                videoCapture = null
                analysis = null
                state.cameraReady = false

                engineScope.launch {
                    val result = withTimeoutOrNull(1500L) {
                        closedDeferred.await()
                    }
                    mainHandler.post {
                        cam.cameraInfo.cameraState.removeObserver(observer)
                        if (result != null) {
                            LensDebugLog.log("CameraX camera state CLOSED reached")
                        } else {
                            LensDebugLog.log("CameraX camera state wait timed out after 1.5s, proceeding with openCamera2")
                        }
                        zoomFactor = targetLens.baseZoomRatio
                        openCamera2(targetLens)
                    }
                }
                return
            } else {
                LensDebugLog.log("unbindAll called (no active camera)")
                provider?.unbindAll()
                zoomFactor = targetLens.baseZoomRatio
                openCamera2(targetLens)
                return
            }
        }

        // Returning from an independent Camera2 lens must be serialized after CameraDevice.onClosed.
        // CameraX can otherwise race the old Camera2 device and fail to bind the main camera.
        if (isCamera2Active) {
            val closingCameraId = camera2Device?.id
            Log.i(TAG, "[LENS_SWITCH_CAMX] Closing Camera2 ID $closingCameraId and returning to ${targetLens.displayName}")
            LensDebugLog.log("return to CameraX requested: target=${targetLens.displayName}, closingCameraId=$closingCameraId")
            closeCamera2(keepTexture = false)
            val deferred = camera2CloseDeferred
            engineScope.launch {
                val closeConfirmed = if (deferred != null) {
                    withTimeoutOrNull(4000L) {
                        deferred.await()
                        true
                    } ?: false
                } else {
                    true
                }
                mainHandler.post {
                    if (switchGen != lensSwitchGeneration.get()) {
                        LensDebugLog.log("stale CameraX return ignored for ${targetLens.displayName} (switch generation changed)")
                        return@post
                    }
                    LensDebugLog.log("Camera2 close confirmed=$closeConfirmed for ID $closingCameraId")
                    if (!closeConfirmed) {
                        Log.w(TAG, "[LENS_SWITCH_CAMX] Camera2 onClosed not confirmed within 4s; attempting CameraX rebind")
                    }

                    // Restore the selected CameraX lens explicitly. Never leave the old hidden
                    // ultra-wide as the active/target lens while binding the main camera.
                    activeDiscoveredLens = targetLens
                    targetDiscoveredLens = targetLens
                    desiredZoom = targetZoom
                    state.zoom = targetZoom
                    if (targetLens.isPrimaryMain || targetLens.lensType == LensType.WIDE) {
                        zoomFactor = 1f
                        boundPhysicalId = null
                        if (settings.manualLens) state.manualLensId = null
                    } else if (settings.manualLens) {
                        state.manualLensId = targetLens.physicalCameraId ?: targetLens.cameraId
                    }

                    if (settings.backgroundLensPreparation && backgroundLensManager.isStandbyReady() && backgroundLensManager.getStandbyLens()?.cameraId == targetLens.cameraId) {
                        Log.i(TAG, "[LENS_SWITCH] Background lens is READY for ${targetLens.displayName}")
                    }
                    LensDebugLog.log("starting CameraX rebind for ${targetLens.displayName} at zoom=$desiredZoom")
                    rebind()
                }
            }
            return
        }

        if (settings.backgroundLensPreparation && backgroundLensManager.isStandbyReady() && backgroundLensManager.getStandbyLens()?.cameraId == targetLens.cameraId) {
            Log.i(TAG, "[LENS_SWITCH] Background lens is READY for ${targetLens.displayName}")
        }

        if (settings.manualLens) {
            state.manualLensId = targetLens.physicalCameraId ?: targetLens.cameraId
        }

        rebind()
    }

    private fun scheduleZoomPreviewUpdate(immediate: Boolean = false, explicitZoom: Float? = null) {
        val zoom = explicitZoom ?: desiredZoom
        pendingZoomUpdateTarget = zoom
        val now = SystemClock.uptimeMillis()
        if (immediate || now - lastZoomPreviewUpdateTimeMs >= 16L) {
            pendingZoomRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingZoomRunnable = null
            lastZoomPreviewUpdateTimeMs = now
            if (camera != null || isCamera2Active) applyZoomInternal(zoom, isContinuous = immediate)
            else if (isSwitchingLens.get()) pendingZoomWhileSwitching = zoom
            backgroundLensManager.updateZoom(
                if (zoomTransitionController.previewOverlapState.value.isOverlapping)
                    zoomTransitionController.currentInterpolatedZoom.value else zoom,
                zoomTransitionController.previewOverlapState.value.isOverlapping,
                _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE
            )
            return
        }
        if (pendingZoomRunnable != null) return
        val delayMs = (16L - (now - lastZoomPreviewUpdateTimeMs)).coerceIn(2L, 16L)
        val runnable = Runnable {
            pendingZoomRunnable = null
            lastZoomPreviewUpdateTimeMs = SystemClock.uptimeMillis()
            val latest = pendingZoomUpdateTarget
            if (camera != null || isCamera2Active) applyZoomInternal(latest, isContinuous = false)
            else if (isSwitchingLens.get()) pendingZoomWhileSwitching = latest
            backgroundLensManager.updateZoom(
                if (zoomTransitionController.previewOverlapState.value.isOverlapping)
                    zoomTransitionController.currentInterpolatedZoom.value else latest,
                zoomTransitionController.previewOverlapState.value.isOverlapping,
                _activePreviewSource.value == PreviewStreamSource.ULTRAWIDE
            )
        }
        pendingZoomRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** VCamera-style completion: release the in-flight lock and immediately consume the newest queued request. */
    private fun completeLensSwitch(
        configuredLens: LensInfo? = targetDiscoveredLens ?: activeDiscoveredLens,
        isContinuousTransition: Boolean = false
    ) {
        if (configuredLens != null) {
            activeDiscoveredLens = configuredLens
            _activePreviewSource.value = if (configuredLens.lensType == LensType.ULTRAWIDE)
                PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        }
        val nextLens = pendingLensWhileSwitching
        val nextZoom = pendingZoomWhileSwitching
        val nextIsContinuous = pendingIsContinuousTransitionWhileSwitching || isContinuousTransition
        pendingLensWhileSwitching = null
        pendingZoomWhileSwitching = null
        pendingIsContinuousTransitionWhileSwitching = false
        pendingZoomPresetTapWhileSwitching = false
        if (nextZoom != null) {
            desiredZoom = nextZoom
            state.zoom = nextZoom
        }
        targetDiscoveredLens = null
        isSwitchingLens.set(false)

        val active = activeDiscoveredLens
        val mismatch = nextLens != null && (active == null ||
            nextLens.id != active.id ||
            nextLens.cameraId != active.cameraId ||
            nextLens.physicalCameraId != active.physicalCameraId ||
            nextLens.lensType != active.lensType
        )
        if (mismatch) {
            val zoom = nextZoom ?: desiredZoom
            mainHandler.post { selectDiscoveredLens(nextLens!!, zoom, nextIsContinuous) }
        } else if (nextZoom != null) {
            mainHandler.post { scheduleZoomPreviewUpdate(immediate = true, explicitZoom = nextZoom) }
        }
    }

    fun backgroundLensPrepChanged() {
        if (state.lensFacing != CameraSelector.LENS_FACING_BACK) {
            backgroundLensManager.release()
            return
        }
        val backLenses = inventory.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }

        if (settings.isBackgroundLensPreparationUnset() && settings.instantLensSwitching && mainLens != null && ultraWideLens != null) {
            val mode = backgroundLensManager.detectSimultaneousStreamingMode(mainLens, ultraWideLens)
            if (mode == io.github.realkhfan.foco.camera.lens.DualStreamingMode.CONCURRENT_DEVICES ||
                mode == io.github.realkhfan.foco.camera.lens.DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
            ) {
                settings.backgroundLensPreparation = true
                Log.i(TAG, "[STANDBY_READY] auto-enabled Keep Ultra Wide Ready; dualMode=$mode")
            }
        }

        if (settings.backgroundLensPreparation) {
            backgroundLensManager.prepareSecondaryLens(mainLens, ultraWideLens)
            Log.i(TAG, "[STANDBY_READY] requested=${settings.backgroundLensPreparation} instant=${settings.instantLensSwitching} ready=${backgroundLensManager.isStandbyReady()} lens=${backgroundLensManager.getStandbyLens()?.displayName}")
        } else {
            backgroundLensManager.release()
        }
    }

    fun instantLensSwitchingChanged() {
        // "Instant Lens Switching" uses the same persistent Keep Ultra Wide Ready stream.
        // It never opens a camera itself; readiness is owned by BackgroundLensManager.
        backgroundLensPrepChanged()
    }

    fun autoLensSelectionChanged() {
        // Preference updated in settings
    }

    fun zoomTransitionAnimChanged() {
        zoomTransitionController.isOverlapAnimationEnabled = settings.zoomTransitionAnimation
    }

    fun lensHandoffSettingChanged() {
        // Preference updated in settings
    }

    private fun applyZoom(zoom: Float) {
        applyZoomInternal(zoom, isContinuous = false)
    }

    fun resetZoom() {
        desiredZoom = 1f
    }

    fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (isCamera2Active && camera2Device != null && camera2Session != null) {
            val req = camera2RequestBuilder ?: return
            val chars = try { cameraManager?.getCameraCharacteristics(camera2Device!!.id) } catch (_: Throwable) { null }
            val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null) {
                val viewW = previewView?.width?.toFloat()?.coerceAtLeast(1f) ?: 1f
                val viewH = previewView?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
                val normX = (x / viewW).coerceIn(0f, 1f)
                val normY = (y / viewH).coerceIn(0f, 1f)
                val centerX = (activeArray.left + normX * activeArray.width()).toInt()
                val centerY = (activeArray.top + normY * activeArray.height()).toInt()
                val halfSize = (activeArray.width() * 0.05f).toInt()
                val rect = Rect(
                    (centerX - halfSize).coerceAtLeast(activeArray.left),
                    (centerY - halfSize).coerceAtLeast(activeArray.top),
                    (centerX + halfSize).coerceAtMost(activeArray.right),
                    (centerY + halfSize).coerceAtMost(activeArray.bottom)
                )
                val metering = android.hardware.camera2.params.MeteringRectangle(rect, 1000)
                req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
                req.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                try {
                    camera2Session?.capture(req.build(), null, camera2Handler)
                    req.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    camera2Session?.setRepeatingRequest(req.build(), null, camera2Handler)
                } catch (_: Throwable) {}
            }
            return
        }
        val cam = camera ?: return
        val view = previewView ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() else setAutoCancelDuration(5, TimeUnit.SECONDS) }
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        analysis?.targetRotation = rotation
        if (recording == null) videoCapture?.targetRotation = rotation
    }

    fun mustPlayShutterSound(): Boolean = runCatching { CameraInfo.mustPlayShutterSound() }.getOrDefault(false)

    // ------------------------------------------------------------------ processed photos (HDR / Night / zoom)

    /** What the next processed photo would be. */
    fun planShot(night: Boolean, nightSeconds: Float): ShotPlan {
        val caps = state.caps
        when (state.photoMode) {
            PhotoMode.LONG_EXPOSURE -> return ShotPlanner.longExposure(
                state.dark, meteredShutterNs, meteredIso, ShotPlanner.motionFor(motionProvider()),
                caps.shutterMaxNs, caps.isoMin, caps.isoMax,
            )
            PhotoMode.ACTION_PAN -> return ShotPlanner.actionPan()
            else -> Unit
        }
        if (night) {
            return ShotPlanner.night(
                nightSeconds, meteredShutterNs, meteredIso, ShotPlanner.motionFor(motionProvider()),
                caps.shutterMaxNs, caps.isoMin, caps.isoMax,
            )
        }
        val digitalZoom = state.zoom / activeLensRatio.coerceAtLeast(0.3f)
        return ShotPlanner.daylight(clippedFraction, digitalZoom, settings.zoomDetail)
    }

    /** Starts a processed photo. Returns false if not possible right now (then use [takePhoto]). */
    fun startBurst(plan: ShotPlan, toFile: File?, listener: BurstSession.Listener): Boolean {
        val cam = camera ?: return false
        if (!state.processing || burst != null || analysis == null) return false
        val rotation = runCatching { cam.cameraInfo.getSensorRotationDegrees(targetRotation) }.getOrDefault(0)
        val mirror = state.lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront
        val wide = settings.ratio == RatioSetting.WIDE_9_16
        val night = plan.kind == io.github.realkhfan.foco.processing.ShotKind.NIGHT ||
            plan.kind == io.github.realkhfan.foco.processing.ShotKind.LONG_EXPOSURE && plan.exposureNs != null
        val ultraHdr = settings.ultraHdr && Build.VERSION.SDK_INT >= 34
        val tone = ToneSettings(
            night = night,
            blackLevel = if (night) 0.004f else 0f,
            sharpen = plan.sharpen,
            shadows = (state.pro.shadows + if (night) 0.15f else 0f).coerceIn(-1f, 1f),
            saturation = if (night) 1.12f else 1f,
            headroom = if (!ultraHdr) 1f else if (night) 3f else 4f,
            chromaDenoise = night,
        )
        val meteredNs = meteredShutterNs
        val meteredIsoNow = meteredIso
        val zoomNow = state.zoom
        val strip = settings.stripDeviceInfo
        val location = if (settings.saveLocation) locationProvider() else null
        val focal35 = state.caps.equivalentFocalMm.takeIf { it > 0f }?.let { (it * zoomNow).roundToInt() }
        val session = BurstSession(
            plan = plan,
            rotation = rotation,
            mirror = mirror,
            crop = { w, h -> if (wide) crop16x9(w, h) else null },
            tone = tone,
            ultraHdr = ultraHdr,
            finish = { renderer, hdr, stats ->
                val time = System.currentTimeMillis()
                var jpeg = PhotoWriter.encode(renderer, hdr)
                val meta = PhotoMeta(
                    timeMillis = time,
                    exposureNs = (if (stats.exposureNs > 0) stats.exposureNs else meteredNs),
                    iso = if (stats.iso > 0) stats.iso else meteredIsoNow,
                    aperture = lastAperture,
                    focalLengthMm = lastFocalMm,
                    focal35mm = focal35,
                    location = location,
                    includeDevice = !strip,
                )
                PhotoWriter.exifSegment(context, meta, renderer.outWidth, renderer.outHeight)?.let { jpeg = JpegSplice.insertExif(jpeg, it) }
                PhotoWriter.save(context, jpeg, MediaNaming.baseName(time), toFile)?.toString()
            },
            listener = object : BurstSession.Listener {
                override fun onFirstFrame(preview: android.graphics.Bitmap) = listener.onFirstFrame(preview)
                override fun onProgress(done: Int, total: Int) = listener.onProgress(done, total)
                override fun onCaptured() {
                    endBurst()
                    listener.onCaptured()
                }
                override fun onSaved(uri: String?) = listener.onSaved(uri)
            },
        )
        highlightRequested = false
        burstOverride = burstCameraSettings(plan)
        applyPro()
        burst = session
        val timeout = Runnable { burst?.stop() }
        burstTimeout = timeout
        mainHandler.postDelayed(timeout, (plan.seconds * 1600).toLong() + 3000L)
        return true
    }

    /** Ends the burst early (shutter tapped again during Night). */
    fun stopBurst() {
        burst?.stop()
        pano?.stop()
    }

    /** Starts a panorama. Returns false if not possible right now. */
    fun startPanorama(listener: PanoramaSession.Listener): Boolean {
        val cam = camera ?: return false
        if (!state.processing || burst != null || pano != null || analysis == null) return false
        val rotation = runCatching { cam.cameraInfo.getSensorRotationDegrees(targetRotation) }.getOrDefault(0)
        val caps = state.caps
        val size = analysis?.resolutionInfo?.resolution
        val longSide = size?.let { maxOf(it.width, it.height) } ?: 4000
        // Focal length in pixels of the camera frames; a typical main camera (≈ 70° across the long side) if unknown.
        val focalPx = if (caps.focalMm > 0f && caps.sensorWidthMm > 0f) {
            caps.focalMm / caps.sensorWidthMm * longSide
        } else {
            longSide / 2f / kotlin.math.tan(Math.toRadians(35.0)).toFloat()
        }
        val strip = settings.stripDeviceInfo
        val location = if (settings.saveLocation) locationProvider() else null
        resetPan()
        val session = PanoramaSession(
            rotation = rotation,
            // Zooming in crops and enlarges the frames: same as a longer lens.
            focalFull = focalPx * state.zoom.coerceAtLeast(1f),
            sensors = panSensors,
            save = { w, h, render ->
                val time = System.currentTimeMillis()
                var jpeg = PhotoWriter.encodePixels(w, h, render)
                val meta = PhotoMeta(time, null, null, lastAperture, lastFocalMm, null, location, !strip)
                PhotoWriter.exifSegment(context, meta, w, h)?.let { jpeg = JpegSplice.insertExif(jpeg, it) }
                PhotoWriter.save(context, jpeg, MediaNaming.baseName(time) + ".PANO", null)?.toString()
            },
            listener = object : PanoramaSession.Listener {
                override fun onShot(thumb: android.graphics.Bitmap, yaw: Float, fov: Float) = listener.onShot(thumb, yaw, fov)
                override fun onHint(hint: PanoramaSession.Hint) = listener.onHint(hint)
                override fun onCaptured(frames: Int) {
                    pano = null
                    burstOverride = null
                    applyPro()
                    listener.onCaptured(frames)
                }
                override fun onSaved(uri: String?) = listener.onSaved(uri)
            },
        )
        // Same exposure and colour for every frame, so the joins don't show.
        burstOverride = BurstCamera(null, null)
        applyPro()
        pano = session
        return true
    }

    private fun endBurst() {
        burstTimeout?.let { mainHandler.removeCallbacks(it) }
        burstTimeout = null
        burst = null
        burstOverride = null
        highlightRequested = false
        applyPro()
    }

    private fun burstCameraSettings(plan: ShotPlan): BurstCamera {
        val manualPro = state.pro.shutterNs != null || state.pro.iso != null
        val exposure = plan.exposureNs
        return if (exposure != null && plan.iso != null && state.caps.manualSensor && !manualPro) {
            BurstCamera(exposure, plan.iso, exposure)
        } else {
            BurstCamera(null, null)
        }
    }

    /** Switches the camera to a darker exposure for the highlight-recovery frame. */
    private fun requestHighlightFrame(session: BurstSession) {
        if (burst !== session || !state.caps.manualSensor) {
            session.stop()
            return
        }
        val divisor = session.plan.highlightDivisor ?: 4f
        val caps = state.caps
        val target = (meteredShutterNs / divisor).toLong().coerceIn(caps.shutterMinNs, caps.shutterMaxNs)
        val iso = meteredIso.coerceIn(caps.isoMin, caps.isoMax)
        session.highlightTargetNs = target
        burstOverride = BurstCamera(target, iso)
        applyPro()
    }

    /** Centre 16:9 crop of a 4:3 frame (sensor orientation). */
    private fun crop16x9(w: Int, h: Int): IntArray {
        val long = maxOf(w, h)
        val short = long * 9 / 16
        return if (w >= h) {
            val ch = minOf(h, short)
            intArrayOf(0, (h - ch) / 2, w, ch)
        } else {
            val cw = minOf(w, short)
            intArrayOf((w - cw) / 2, 0, cw, h)
        }
    }

    // ------------------------------------------------------------------ camera's own photos (RAW, flash, 50 MP, video snapshot)

    /**
     * Takes a photo with the camera's own processing into DCIM/Camera (or [toFile] for other apps).
     * [onResult] gets the saved JPEG's URI.
     */
    fun takePhoto(toFile: File?, onResult: (Uri?, Throwable?) -> Unit) {
        if (isCamera2Active && camera2Device != null && camera2Session != null) {
            camera2CaptureStill(toFile, onResult)
            return
        }
        val capture = imageCapture ?: return onResult(null, IllegalStateException("Camera not ready"))
        capture.targetRotation = targetRotation
        if (state.kind == CaptureKind.PHOTO) runCatching { capture.flashMode = photoFlashMode() }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = state.lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront
            if (settings.saveLocation) location = locationProvider()
        }
        var reported = false
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (outputFileResults.imageFormat == ImageFormat.RAW_SENSOR || reported) return
                reported = true
                onResult(outputFileResults.savedUri ?: toFile?.let { Uri.fromFile(it) }, null)
            }

            override fun onError(exception: ImageCaptureException) {
                if (reported) return
                reported = true
                onResult(null, exception)
            }
        }
        if (toFile != null) {
            val options = ImageCapture.OutputFileOptions.Builder(toFile).setMetadata(metadata).build()
            capture.takePicture(options, mainExecutor, callback)
            return
        }
        val name = MediaNaming.baseName(System.currentTimeMillis())
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val jpeg = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, collection, MediaNaming.contentValues(name, "image/jpeg"),
        ).setMetadata(metadata).build()
        if (photoFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) {
            val raw = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver, collection, MediaNaming.contentValues(name, "image/x-adobe-dng"),
            ).setMetadata(metadata).build()
            capture.takePicture(raw, jpeg, mainExecutor, callback)
        } else {
            capture.takePicture(jpeg, mainExecutor, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(toFile: File?, onEvent: (VideoRecordEvent) -> Unit): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        video.targetRotation = targetRotation
        val pending = if (toFile != null) {
            video.output.prepareRecording(context, FileOutputOptions.Builder(toFile).build())
        } else {
            val name = MediaNaming.baseName(System.currentTimeMillis())
            val builder = MediaStoreOutputOptions.Builder(
                context.contentResolver, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ).setContentValues(MediaNaming.contentValues(name, "video/mp4"))
            if (settings.saveLocation) locationProvider()?.let { builder.setLocation(it) }
            video.output.prepareRecording(context, builder.build())
        }
        if (hasAudioPermission()) enableAudio(pending)
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            onEvent(event)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun enableAudio(pending: PendingRecording) {
        pending.withAudioEnabled()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun stopRecording() {
        recording?.stop()
    }

    fun pause() {
        recording?.pause()
    }

    fun resume() {
        recording?.resume()
    }

    companion object {
        private const val TAG = "FocoCamera"

    }
}
