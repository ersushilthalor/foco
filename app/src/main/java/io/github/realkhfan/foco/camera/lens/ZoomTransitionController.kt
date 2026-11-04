package io.github.realkhfan.foco.camera.lens

import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

fun zoomToNormalizedLog(zoom: Float, minZ: Float, maxZ: Float): Float {
    val safeMin = minZ.coerceAtLeast(0.3f)
    val safeMax = maxZ.coerceAtLeast(safeMin + 0.05f)
    val logMin = ln(safeMin.toDouble())
    val logMax = ln(safeMax.toDouble())
    if (logMax <= logMin) return 0f
    val logZ = ln(zoom.coerceIn(safeMin, safeMax).toDouble())
    return ((logZ - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
}

fun normalizedToZoomLog(t: Float, minZ: Float, maxZ: Float): Float {
    val safeMin = minZ.coerceAtLeast(0.3f)
    val safeMax = maxZ.coerceAtLeast(safeMin + 0.05f)
    if (safeMax <= safeMin) return safeMin
    val logMin = ln(safeMin.toDouble())
    val logMax = ln(safeMax.toDouble())
    val logZ = logMin + t.coerceIn(0f, 1f) * (logMax - logMin)
    val rawZoom = exp(logZ).toFloat()
    return rawZoom.coerceIn(safeMin, safeMax)
}

/**
 * Unified Background Zoom Slider & Transition Controller.
 * Replicated from source reference implementation.
 *
 * 1. Uses the requested transition duration (400ms default, 300ms video).
 * 2. Uses logarithmic zoom scale to smoothly interpolate from startZoom to targetZoom.
 * 3. Reuses camera functionality to directly apply calibrated zoom and switch physical lenses
 *    cleanly without tearing down the camera session.
 * 4. Fully supports Ultra-Wide -> 1x and 1x -> Ultra-Wide without requiring user interaction.
 * 5. Instant lens switching, standby camera readiness, and stream stability are strictly preserved.
 * 6. Direct 0.5x <-> 2x/3x preset handoff transitions smoothly across the boundary without stopping at 1x.
 */
class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: LensInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 400L
        const val VIDEO_TRANSITION_DURATION_MS = 300L
        const val ZOOM_UPDATE_INTERVAL_MS = 16L // Fixed 60Hz zoom updates
        const val DEFAULT_HOLD_AT_ONE_X_DURATION_MS = 200L // Hold at exact 1x FOV before physical switch
        private const val HOLD_FOV_CONFIRMATION_FRAMES = 2L
        private const val HOLD_FOV_FRAME_TIMEOUT_MS = 150L
        const val DEFAULT_OVERLAP_DURATION_MS = 400L
        const val PREVIEW_OVERLAP_DURATION_MS = 100L
        const val MIN_OVERLAP_DURATION_MS = 100L
        const val MAX_OVERLAP_DURATION_MS = 1000L
        private const val FRAME_HANDOFF_WARMUP_FRAMES = 2
        private const val FRAME_HANDOFF_REQUIRED_FRAMES = 16
        private const val FRAME_HANDOFF_TIMEOUT_MS = 900L
        private const val MAIN_FOV_DIVERGENCE_FADE_RANGE = 0.20f
        private const val MAX_HANDOFF_ALPHA_STEP = 0.30f
        private const val FRAME_HANDOFF_PREWARM_MAX_AGE_MS = 120L

        private fun isHalfXZoom(zoom: Float): Boolean = abs(zoom - 0.5f) < 0.025f

        private fun isTelephotoPresetZoom(zoom: Float): Boolean =
            abs(zoom - 2.0f) < 0.05f || abs(zoom - 3.0f) < 0.05f

        /**
         * A direct 0.5x -> 1x tap uses a fixed 200ms hold, never frame handoff.
         */
        fun isHoldTimeHandoffEligible(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXZoom(fromZoom) && abs(targetZoom - 1.0f) < 0.05f

        /** Keeps the direct 0.5x -> 1x motion at its full duration, then adds the hold separately. */
        fun calculateTransitionMotionDurationMs(
            durationMs: Long,
            holdDurationMs: Long,
            isUltraWideToMainSwitch: Boolean,
            isDirectHalfXToOneXHold: Boolean
        ): Long {
            val requestedDuration = durationMs.coerceAtLeast(1L)
            return if (isUltraWideToMainSwitch && !isDirectHalfXToOneXHold) {
                (requestedDuration - holdDurationMs).coerceAtLeast(1L)
            } else {
                requestedDuration
            }
        }

        /** True only for direct 0.5x -> 2x/3x preset-endpoint transitions. */
        fun isHalfXToTelephotoPresetTransition(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXZoom(fromZoom) && isTelephotoPresetZoom(targetZoom)

        /** True only for direct 2x/3x -> 0.5x preset-endpoint transitions. */
        fun isTelephotoPresetToHalfXTransition(fromZoom: Float, targetZoom: Float): Boolean =
            isTelephotoPresetZoom(fromZoom) && isHalfXZoom(targetZoom)

        /**
         * Calculates alpha progress from real incoming frames and the shared-FOV window.
         */
        fun calculateFrameHandoffProgress(
            incomingFrameCount: Int,
            targetSource: PreviewStreamSource,
            currentZoom: Float,
            previousProgress: Float = 0f
        ): Float {
            val frameProgress = incomingFrameCount.coerceIn(0, FRAME_HANDOFF_REQUIRED_FRAMES).toFloat() /
                FRAME_HANDOFF_REQUIRED_FRAMES.toFloat()
            val frameEased = (0.5 * (1.0 - cos(PI * frameProgress.toDouble()))).toFloat()
            val zoomEased = if (targetSource == PreviewStreamSource.ULTRAWIDE) {
                val zoomProgress = ((1.0f - currentZoom) / (1.0f - 0.5f)).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            } else {
                val zoomProgress = ((currentZoom - 1.0f) / MAIN_FOV_DIVERGENCE_FADE_RANGE).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            }
            val previous = previousProgress.coerceIn(0f, 1f)
            val desired = maxOf(frameEased, zoomEased)
            return minOf(desired, previous + MAX_HANDOFF_ALPHA_STEP).coerceIn(previous, 1f)
        }

        fun isFrameBasedHandoffEligible(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXToTelephotoPresetTransition(fromZoom, targetZoom) ||
                isTelephotoPresetToHalfXTransition(fromZoom, targetZoom)

        private fun easeZoomTransitionProgress(progress: Double): Double {
            val t = progress.coerceIn(0.0, 1.0)
            return (0.45 * t + 2.10 * t * t - 1.55 * t * t * t).coerceIn(0.0, 1.0)
        }

        fun calculateZoomTransitionElapsedTimestamps(
            stepCount: Int,
            durationMs: Long = TOTAL_TRANSITION_DURATION_MS
        ): LongArray {
            val steps = stepCount.coerceAtLeast(1)
            val timestamps = LongArray(steps + 1)
            for (stepIndex in 1 until steps) {
                val targetProgress = stepIndex.toDouble() / steps.toDouble()
                var low = 0.0
                var high = 1.0
                repeat(32) {
                    val mid = (low + high) / 2.0
                    if (easeZoomTransitionProgress(mid) < targetProgress) {
                        low = mid
                    } else {
                        high = mid
                    }
                }
                timestamps[stepIndex] = (high * durationMs.toDouble() + 0.5).toLong()
            }
            timestamps[0] = 0L
            timestamps[steps] = durationMs
            return timestamps
        }

        fun calculateSinusoidalElapsedTimestamps(
            stepCount: Int,
            durationMs: Long = TOTAL_TRANSITION_DURATION_MS
        ): LongArray {
            val targetElapsedMs = LongArray(stepCount + 1)
            for (stepIndex in 0..stepCount) {
                val progress = (stepIndex.toDouble() / stepCount.toDouble()).coerceIn(0.0, 1.0)
                val cosVal = (1.0 - 2.0 * progress).coerceIn(-1.0, 1.0)
                val elapsed = (durationMs.toDouble() / PI) * acos(cosVal)
                targetElapsedMs[stepIndex] = (elapsed + 0.5).toLong()
            }
            return targetElapsedMs
        }
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS
    var isOverlapAnimationEnabled: Boolean = true

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private val _ultraWideHoldFrameSequence = MutableStateFlow(0L)

    private var pendingFrameHandoffTarget: PreviewStreamSource? = null
    private var frameHandoffFromSource: PreviewStreamSource? = null
    private var frameHandoffFrameCount: Int = 0
    private var frameHandoffTimeoutJob: Job? = null

    private var preparedFrameHandoffFromSource: PreviewStreamSource? = null
    private var preparedFrameHandoffTargetSource: PreviewStreamSource? = null
    private var preparedTargetFrameCount: Int = 0
    private var preparedTargetLastFrameAtMs: Long = 0L
    private var activeTransitionJob: Job? = null

    fun zoomToNormalizedSliderProgress(zoom: Float, minZoom: Float = 0.5f, maxZoom: Float = 20.0f): Float =
        zoomToNormalizedLog(zoom, minZoom, maxZoom)

    fun normalizedSliderProgressToZoom(progress: Float, minZoom: Float = 0.5f, maxZoom: Float = 20.0f): Float =
        normalizedToZoomLog(progress, minZoom, maxZoom)

    fun generateContinuousZoomSteps(startZoom: Float, endZoom: Float): List<Float> {
        val startZ = ((startZoom * 100f).roundToInt() / 100f)
        val endZ = ((endZoom * 100f).roundToInt() / 100f)
        val stepCount = max(1, (abs(endZ - startZ) * 100f).roundToInt())
        val steps = ArrayList<Float>(stepCount + 1)
        for (stepIndex in 0..stepCount) {
            val t = stepIndex.toFloat() / stepCount.toFloat()
            val interpolated = startZ + (endZ - startZ) * t
            val rounded = (interpolated * 100f).roundToInt() / 100f
            steps.add(rounded)
        }
        return steps
    }

    fun setSteadyStatePreview(isUltraWide: Boolean) {
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = false,
            mainAlpha = if (isUltraWide) 0.0f else 1.0f,
            ultraWideAlpha = if (isUltraWide) 1.0f else 0.0f,
            activeSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN,
            overlapProgress = 0.0f,
            topSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        )
    }

    fun prepareFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource
    ) {
        if (fromSource == targetSource) {
            clearPreparedFrameHandoff()
            return
        }
        preparedFrameHandoffFromSource = fromSource
        preparedFrameHandoffTargetSource = targetSource
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    private fun clearPreparedFrameHandoff() {
        preparedFrameHandoffFromSource = null
        preparedFrameHandoffTargetSource = null
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    fun beginFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource,
        scope: CoroutineScope
    ) {
        frameHandoffTimeoutJob?.cancel()
        val nowMs = SystemClock.uptimeMillis()
        val hasFreshPreparedTargetFrames =
            preparedFrameHandoffFromSource == fromSource &&
                preparedFrameHandoffTargetSource == targetSource &&
                preparedTargetFrameCount >= FRAME_HANDOFF_WARMUP_FRAMES &&
                preparedTargetLastFrameAtMs > 0L &&
                nowMs - preparedTargetLastFrameAtMs <= FRAME_HANDOFF_PREWARM_MAX_AGE_MS
        clearPreparedFrameHandoff()

        if (fromSource == targetSource) {
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            return
        }

        frameHandoffFromSource = fromSource
        pendingFrameHandoffTarget = targetSource
        frameHandoffFrameCount = if (hasFreshPreparedTargetFrames) FRAME_HANDOFF_WARMUP_FRAMES else 0
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
            ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
            activeSource = targetSource,
            overlapProgress = 0f,
            topSource = fromSource
        )

        frameHandoffTimeoutJob = scope.launch {
            delay(FRAME_HANDOFF_TIMEOUT_MS)
            if (pendingFrameHandoffTarget == targetSource) {
                pendingFrameHandoffTarget = null
                frameHandoffFromSource = null
                frameHandoffTimeoutJob = null
                setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            }
        }
    }

    fun onPreviewFrameAvailable(source: PreviewStreamSource) {
        if (source == PreviewStreamSource.ULTRAWIDE) {
            _ultraWideHoldFrameSequence.value = _ultraWideHoldFrameSequence.value + 1L
        }
        val targetSource = pendingFrameHandoffTarget
        if (targetSource == null) {
            if (source == preparedFrameHandoffTargetSource) {
                preparedTargetFrameCount =
                    (preparedTargetFrameCount + 1).coerceAtMost(FRAME_HANDOFF_WARMUP_FRAMES)
                preparedTargetLastFrameAtMs = SystemClock.uptimeMillis()
            }
            return
        }
        if (source != targetSource) return

        frameHandoffFrameCount = (frameHandoffFrameCount + 1).coerceAtMost(
            FRAME_HANDOFF_WARMUP_FRAMES + FRAME_HANDOFF_REQUIRED_FRAMES
        )
        val fromSource = frameHandoffFromSource ?: return

        if (frameHandoffFrameCount <= FRAME_HANDOFF_WARMUP_FRAMES) {
            _previewOverlapState.value = PreviewOverlapState(
                isOverlapping = true,
                mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
                ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
                activeSource = targetSource,
                overlapProgress = 0f,
                topSource = fromSource
            )
            return
        }

        val blendFrameCount = frameHandoffFrameCount - FRAME_HANDOFF_WARMUP_FRAMES
        val previousProgress = _previewOverlapState.value.overlapProgress
        val progress = calculateFrameHandoffProgress(
            incomingFrameCount = blendFrameCount,
            targetSource = targetSource,
            currentZoom = _currentInterpolatedZoom.value,
            previousProgress = previousProgress
        )
        val mainAlpha = if (targetSource == PreviewStreamSource.MAIN) progress else 1f
        val ultraWideAlpha = if (targetSource == PreviewStreamSource.ULTRAWIDE) progress else 1f

        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = mainAlpha,
            ultraWideAlpha = ultraWideAlpha,
            activeSource = targetSource,
            overlapProgress = progress,
            topSource = targetSource
        )

        if (blendFrameCount >= FRAME_HANDOFF_REQUIRED_FRAMES) {
            frameHandoffTimeoutJob?.cancel()
            frameHandoffTimeoutJob = null
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
        }
    }

    private fun cancelFrameBasedHandoff() {
        val state = _previewOverlapState.value
        val keepUltraWide = if (state.isOverlapping) {
            state.ultraWideAlpha >= state.mainAlpha
        } else {
            state.activeSource == PreviewStreamSource.ULTRAWIDE
        }
        frameHandoffTimeoutJob?.cancel()
        frameHandoffTimeoutJob = null
        pendingFrameHandoffTarget = null
        frameHandoffFromSource = null
        frameHandoffFrameCount = 0
        clearPreparedFrameHandoff()
        setSteadyStatePreview(keepUltraWide)
    }

    fun startTransition(
        fromZoom: Float,
        targetZoom: Float,
        targetLens: LensInfo?,
        availableLenses: List<LensInfo>,
        currentLens: LensInfo?,
        switchPointMm: Float,
        scope: CoroutineScope,
        durationMs: Long = TOTAL_TRANSITION_DURATION_MS,
        skipHoldAtSwitchPoint: Boolean = false,
        onZoomUpdate: (Float) -> Unit,
        onComplete: (Float, LensInfo?) -> Unit
    ): Job {
        cancelTransition()

        val transitionDurationMs = durationMs.coerceAtLeast(1L)
        val transitionFrameIntervalMs = ZOOM_UPDATE_INTERVAL_MS

        val job = scope.launch(Dispatchers.Main.immediate) {
            val currentFacing = currentLens?.facing
            val lensesForFacing = availableLenses.filter { currentFacing == null || it.facing == currentFacing }
            val ultraWideLens = lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            val mainWideLens = lensesForFacing.firstOrNull { it.isPrimaryMain }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE }
                ?: lensesForFacing.firstOrNull()

            val isFrontCamera = currentFacing == CameraCharacteristics.LENS_FACING_FRONT
            val minZoom = if (isFrontCamera) 0.8f else if (ultraWideLens != null) 0.5f else 1.0f
            val maxLensZoom = lensesForFacing.maxOfOrNull { it.maxZoomRatio } ?: 20.0f
            val maxZoom = maxOf(maxLensZoom, 20.0f)

            val startZ = ((fromZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)
            val endZ = ((targetZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)

            if (abs(endZ - startZ) < 0.005f) {
                _currentInterpolatedZoom.value = endZ
                onZoomUpdate(endZ)
                onApplyZoomToEngine(endZ, false)
                val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)
                setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
                _isTransitionActive.value = false
                onComplete(endZ, targetLens)
                return@launch
            }

            _isTransitionActive.value = true

            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)

            val isStartingOnUltraWide = (currentLens?.lensType == LensType.ULTRAWIDE || startZ < switchZoom)
            val isTappingOneX = abs(endZ - 1.0f) < 0.05f
            val isCrossingOrAtSwitchPoint = (endZ >= switchZoom - 0.05f) || isTappingOneX
            val effectiveSwitchZoom = if ((isTappingOneX && switchZoom > 1.0f) || isHalfXToTelephotoPresetTransition(startZ, endZ)) 1.0f else switchZoom
            val isUltraWideToMainSwitch = !isFrontCamera && isStartingOnUltraWide &&
                isCrossingOrAtSwitchPoint &&
                ultraWideLens != null && mainWideLens != null &&
                (targetLens == null || targetLens.isPrimaryMain || targetLens.lensType == LensType.WIDE || targetLens.lensType == LensType.TELEPHOTO || targetLens.lensType == LensType.TELEPHOTO_3X)
            val isDirectHalfXToTelephotoPreset = isHalfXToTelephotoPresetTransition(startZ, endZ)

            val holdDuration = if (skipHoldAtSwitchPoint || isDirectHalfXToTelephotoPreset) {
                0L
            } else {
                DEFAULT_HOLD_AT_ONE_X_DURATION_MS
            }
            val transitionMotionDurationMs = calculateTransitionMotionDurationMs(
                durationMs = transitionDurationMs,
                holdDurationMs = holdDuration,
                isUltraWideToMainSwitch = isUltraWideToMainSwitch,
                isDirectHalfXToOneXHold = isHoldTimeHandoffEligible(startZ, endZ)
            )

            if (isUltraWideToMainSwitch) {
                setSteadyStatePreview(true)
                val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
                val switchNorm = zoomToNormalizedLog(effectiveSwitchZoom, minZoom, maxZoom)
                val endNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

                val isTargetNearSwitch = abs(endZ - effectiveSwitchZoom) <= 0.08f || endZ <= effectiveSwitchZoom + 0.05f

                if (!isTargetNearSwitch && (skipHoldAtSwitchPoint || isDirectHalfXToTelephotoPreset)) {
                    val useFrameBasedHandoff = isOverlapAnimationEnabled &&
                        isFrameBasedHandoffEligible(startZ, endZ)
                    if (useFrameBasedHandoff) {
                        prepareFrameBasedHandoff(
                            fromSource = PreviewStreamSource.ULTRAWIDE,
                            targetSource = PreviewStreamSource.MAIN
                        )
                    }
                    val startTime = SystemClock.uptimeMillis()
                    var mainLensSelected = false

                    if (isDirectHalfXToTelephotoPreset) {
                        val stepCount = ((endZ - startZ) / 0.05f).roundToInt().coerceAtLeast(1)
                        val targetElapsedMs = calculateZoomTransitionElapsedTimestamps(stepCount, transitionMotionDurationMs)
                        for (stepIndex in 0..stepCount) {
                            if (!isActive) break
                            val deadline = startTime + targetElapsedMs[stepIndex]
                            val waitMs = deadline - SystemClock.uptimeMillis()
                            if (waitMs > 0L) delay(waitMs)

                            val zoomValue = if (stepIndex == stepCount) {
                                endZ
                            } else {
                                (startZ + stepIndex * 0.05f).coerceIn(startZ, endZ)
                            }.let { (it * 100f).roundToInt() / 100f }

                            if (!mainLensSelected && zoomValue >= effectiveSwitchZoom - 0.0001f) {
                                _currentInterpolatedZoom.value = effectiveSwitchZoom
                                onZoomUpdate(effectiveSwitchZoom)
                                onApplyZoomToEngine(effectiveSwitchZoom, true)
                                if (useFrameBasedHandoff) {
                                    beginFrameBasedHandoff(
                                        fromSource = PreviewStreamSource.ULTRAWIDE,
                                        targetSource = PreviewStreamSource.MAIN,
                                        scope = scope
                                    )
                                }
                                onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                                if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                                mainLensSelected = true
                            }

                            _currentInterpolatedZoom.value = zoomValue
                            onZoomUpdate(zoomValue)
                            onApplyZoomToEngine(zoomValue, true)
                        }
                    } else {
                        var lastDispatchedZoom = startZ
                        var lastDispatchedTime = 0L
                        while (isActive) {
                            val now = SystemClock.uptimeMillis()
                            val elapsed = now - startTime
                            if (elapsed >= transitionMotionDurationMs) break

                            val linearProgress = (elapsed.toDouble() / transitionMotionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                            val smoothProgress = easeZoomTransitionProgress(linearProgress)
                            val currentNorm = (startNorm + (endNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)
                            val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                            val currentZ = rawZoom.coerceIn(minZoom, maxZoom)
                            val reachedSwitchPoint = currentNorm >= switchNorm || currentZ >= effectiveSwitchZoom

                            if (!mainLensSelected && reachedSwitchPoint) {
                                _currentInterpolatedZoom.value = effectiveSwitchZoom
                                if (abs(lastDispatchedZoom - effectiveSwitchZoom) >= 0.005f) {
                                    onZoomUpdate(effectiveSwitchZoom)
                                }
                                onApplyZoomToEngine(effectiveSwitchZoom, true)
                                if (useFrameBasedHandoff) {
                                    beginFrameBasedHandoff(
                                        fromSource = PreviewStreamSource.ULTRAWIDE,
                                        targetSource = PreviewStreamSource.MAIN,
                                        scope = scope
                                    )
                                }
                                onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                                if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                                mainLensSelected = true
                                lastDispatchedZoom = effectiveSwitchZoom
                                lastDispatchedTime = now
                                if (currentZ > effectiveSwitchZoom + 0.005f) {
                                    _currentInterpolatedZoom.value = currentZ
                                    onZoomUpdate(currentZ)
                                    onApplyZoomToEngine(currentZ, true)
                                    lastDispatchedZoom = currentZ
                                    lastDispatchedTime = now
                                }
                            } else {
                                val zoomToApply = if (mainLensSelected) currentZ else currentZ.coerceAtMost(effectiveSwitchZoom)
                                if (abs(zoomToApply - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= transitionFrameIntervalMs) {
                                    lastDispatchedZoom = zoomToApply
                                    lastDispatchedTime = now
                                    _currentInterpolatedZoom.value = zoomToApply
                                    onZoomUpdate(zoomToApply)
                                    onApplyZoomToEngine(zoomToApply, true)
                                }
                            }

                            val nextTick = minOf(startTime + transitionMotionDurationMs, now + transitionFrameIntervalMs)
                            val waitMs = nextTick - SystemClock.uptimeMillis()
                            if (waitMs > 0) delay(waitMs)
                        }
                    }

                    val totalElapsed = SystemClock.uptimeMillis() - startTime
                    if (totalElapsed < transitionMotionDurationMs) {
                        delay(transitionMotionDurationMs - totalElapsed)
                    }

                    if (!mainLensSelected) {
                        onApplyZoomToEngine(effectiveSwitchZoom, true)
                        if (useFrameBasedHandoff) {
                            beginFrameBasedHandoff(
                                fromSource = PreviewStreamSource.ULTRAWIDE,
                                targetSource = PreviewStreamSource.MAIN,
                                scope = scope
                            )
                        }
                        onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                        if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                    }

                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    if (pendingFrameHandoffTarget == null) setSteadyStatePreview(false)
                    _isTransitionActive.value = false
                    activeTransitionJob = null
                    onComplete(endZ, targetLens ?: mainWideLens)
                    return@launch
                }

                val uwPhaseDurationMs = if (isTargetNearSwitch) {
                    transitionMotionDurationMs
                } else {
                    val totalSpan = abs(endNorm - startNorm).coerceAtLeast(0.001f)
                    val uwSpan = abs(switchNorm - startNorm)
                    ((uwSpan / totalSpan) * transitionMotionDurationMs.toFloat()).toLong().coerceIn(60L, transitionMotionDurationMs)
                }

                val startTime = SystemClock.uptimeMillis()
                var lastDispatchedZoom = startZ
                var lastDispatchedTime = 0L

                while (isActive) {
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now - startTime
                    if (elapsed >= uwPhaseDurationMs) break

                    val linearProgress = (elapsed.toDouble() / uwPhaseDurationMs.toDouble()).coerceIn(0.0, 1.0)
                    val smoothProgress = easeZoomTransitionProgress(linearProgress)
                    val currentNorm = (startNorm + (switchNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                    val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                    val currentZ = rawZoom.coerceIn(minZoom, effectiveSwitchZoom)

                    if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= transitionFrameIntervalMs) {
                        lastDispatchedZoom = currentZ
                        lastDispatchedTime = now
                        _currentInterpolatedZoom.value = currentZ
                        onZoomUpdate(currentZ)
                        onApplyZoomToEngine(currentZ, true)
                    }

                    val nextTick = minOf(startTime + uwPhaseDurationMs, now + transitionFrameIntervalMs)
                    val waitMs = nextTick - SystemClock.uptimeMillis()
                    if (waitMs > 0) delay(waitMs)
                }

                val totalElapsedUw = SystemClock.uptimeMillis() - startTime
                if (totalElapsedUw < uwPhaseDurationMs) {
                    delay(uwPhaseDurationMs - totalElapsedUw)
                }

                val usesHoldTimeHandoff = isHoldTimeHandoffEligible(startZ, endZ)
                _currentInterpolatedZoom.value = effectiveSwitchZoom
                onZoomUpdate(effectiveSwitchZoom)
                val engineZoomAtSwitch = if (usesHoldTimeHandoff) {
                    (effectiveSwitchZoom - 0.001f).coerceAtLeast(minZoom)
                } else {
                    effectiveSwitchZoom
                }
                val holdFrameSequenceBeforeApply = _ultraWideHoldFrameSequence.value
                onApplyZoomToEngine(engineZoomAtSwitch, true)

                val isDirectTelephotoTarget = isDirectHalfXToTelephotoPreset
                val useFrameBasedHandoff = !usesHoldTimeHandoff &&
                    isOverlapAnimationEnabled && isFrameBasedHandoffEligible(startZ, endZ)
                val mainLensPreparedInOverlap = !usesHoldTimeHandoff &&
                    isOverlapAnimationEnabled && (holdDuration > 0L || isDirectTelephotoTarget || useFrameBasedHandoff)
                var frameBasedHandoffStarted = false

                if (usesHoldTimeHandoff) {
                    if (holdDuration > 0L) {
                        withTimeoutOrNull(HOLD_FOV_FRAME_TIMEOUT_MS) {
                            _ultraWideHoldFrameSequence.first {
                                it >= holdFrameSequenceBeforeApply + HOLD_FOV_CONFIRMATION_FRAMES
                            }
                        }
                        delay(holdDuration)
                    }
                    onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                } else if (mainLensPreparedInOverlap) {
                    if (isDirectTelephotoTarget || useFrameBasedHandoff) {
                        beginFrameBasedHandoff(
                            fromSource = PreviewStreamSource.ULTRAWIDE,
                            targetSource = PreviewStreamSource.MAIN,
                            scope = scope
                        )
                        frameBasedHandoffStarted = true
                        onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                    } else {
                        onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                        if (holdDuration > 0L) {
                            val blendDurationMs = holdDuration
                            val blendStartTime = SystemClock.uptimeMillis()
                            while (isActive) {
                                val now = SystemClock.uptimeMillis()
                                val blendElapsed = now - blendStartTime
                                if (blendElapsed >= blendDurationMs) break

                                val progress = (blendElapsed.toFloat() / blendDurationMs.toFloat()).coerceIn(0f, 1f)
                                val smoothProgress = (0.5f * (1.0f - cos(PI * progress.toDouble()))).toFloat().coerceIn(0f, 1f)
                                val uwAlpha = (1.0f - smoothProgress).coerceIn(0f, 1f)
                                val mainAlpha = 1.0f

                                _previewOverlapState.value = PreviewOverlapState(
                                    isOverlapping = true,
                                    mainAlpha = mainAlpha,
                                    ultraWideAlpha = uwAlpha,
                                    activeSource = PreviewStreamSource.MAIN,
                                    overlapProgress = progress,
                                    topSource = PreviewStreamSource.ULTRAWIDE
                                )

                                val nextTick = minOf(blendStartTime + blendDurationMs, now + ZOOM_UPDATE_INTERVAL_MS)
                                val waitMs = nextTick - SystemClock.uptimeMillis()
                                if (waitMs > 0) delay(waitMs)
                            }
                        }
                    }
                } else {
                    delay(holdDuration)
                }

                if (!mainLensPreparedInOverlap && !usesHoldTimeHandoff) {
                    onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, false)
                }
                if (!frameBasedHandoffStarted) setSteadyStatePreview(false)

                if (isTargetNearSwitch) {
                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    _isTransitionActive.value = false
                    activeTransitionJob = null
                    onComplete(endZ, mainWideLens)
                    return@launch
                } else {
                    val mainPhaseDurationMs = (transitionMotionDurationMs - uwPhaseDurationMs).coerceAtLeast(1L)
                    val mainStartTime = SystemClock.uptimeMillis()
                    lastDispatchedZoom = effectiveSwitchZoom
                    lastDispatchedTime = 0L

                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        val elapsed = now - mainStartTime
                        if (elapsed >= mainPhaseDurationMs) break

                        val linearProgress = (elapsed.toDouble() / mainPhaseDurationMs.toDouble()).coerceIn(0.0, 1.0)
                        val smoothProgress = easeZoomTransitionProgress(linearProgress)
                        val currentNorm = (switchNorm + (endNorm - switchNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                        val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                        val currentZ = rawZoom.coerceIn(effectiveSwitchZoom, maxZoom)

                        if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= transitionFrameIntervalMs) {
                            lastDispatchedZoom = currentZ
                            lastDispatchedTime = now
                            _currentInterpolatedZoom.value = currentZ
                            onZoomUpdate(currentZ)
                            onApplyZoomToEngine(currentZ, true)
                        }

                        val nextTick = minOf(mainStartTime + mainPhaseDurationMs, now + transitionFrameIntervalMs)
                        val waitMs = nextTick - SystemClock.uptimeMillis()
                        if (waitMs > 0) delay(waitMs)
                    }

                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    _isTransitionActive.value = false
                    activeTransitionJob = null
                    onComplete(endZ, targetLens ?: mainWideLens)
                    return@launch
                }
            }

            val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
            val targetNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

            val startTime = SystemClock.uptimeMillis()
            var lastDispatchedZoom = startZ
            var lastDispatchedTime = 0L
            val useReverseFrameBasedHandoff = isOverlapAnimationEnabled &&
                isFrameBasedHandoffEligible(startZ, endZ)
            var reverseFrameBasedHandoffStarted = false

            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= transitionDurationMs) break

                val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                val smoothProgress = easeZoomTransitionProgress(linearProgress)
                val currentNorm = (startNorm + (targetNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                val currentZ = rawZoom.coerceIn(minZoom, maxZoom)

                if (useReverseFrameBasedHandoff && !reverseFrameBasedHandoffStarted && currentZ < switchZoom - 0.005f) {
                    beginFrameBasedHandoff(
                        fromSource = PreviewStreamSource.MAIN,
                        targetSource = PreviewStreamSource.ULTRAWIDE,
                        scope = scope
                    )
                    reverseFrameBasedHandoffStarted = true
                }

                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= transitionFrameIntervalMs) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = now
                    _currentInterpolatedZoom.value = currentZ
                    onZoomUpdate(currentZ)
                    onApplyZoomToEngine(currentZ, true)
                }

                val nextTick = minOf(startTime + transitionDurationMs, now + transitionFrameIntervalMs)
                val waitMs = nextTick - SystemClock.uptimeMillis()
                if (waitMs > 0) delay(waitMs)
            }

            val totalElapsed = SystemClock.uptimeMillis() - startTime
            if (totalElapsed < transitionDurationMs) {
                delay(transitionDurationMs - totalElapsed)
            }

            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, true)

            if (pendingFrameHandoffTarget == null) {
                setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
            }
            _isTransitionActive.value = false
            activeTransitionJob = null

            onComplete(endZ, targetLens)
        }

        activeTransitionJob = job
        return job
    }

    fun cancelTransition() {
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        _isTransitionActive.value = false
        if (pendingFrameHandoffTarget != null || preparedFrameHandoffTargetSource != null) {
            cancelFrameBasedHandoff()
        }
    }
}
