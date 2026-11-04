package io.github.realkhfan.foco.camera.lens

import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Optical and FOV-calibrated camera lens calculations.
 * Replicated from source reference camera implementation.
 *
 * Replaces naive focal length ratios with sensor-aware Field Of View (FOV) calibration:
 * - 1.0x is always the calibrated FOV of the primary Main Wide camera.
 * - Ultra-wide optical ratio is derived from actual horizontal FOV.
 * - Minimum Ultra-wide zoom is strictly wider than 1.0x Main.
 * - At 0.9x, Ultra-wide is never more zoomed in than 1.0x Main.
 * - Dynamic crop mapping converts UI zoom to exact physical sensor crop.
 * - Hysteresis thresholds prevent jittery lens switching during continuous user gestures.
 */
object CameraOpticalCalibration {

    /**
     * Calculates horizontal Field of View in degrees from physical sensor width and focal length.
     * hFOV = 2 * atan(sensorWidth / (2 * focalLength)) * (180 / PI)
     */
    fun calculateHorizontalFovDegrees(sensorWidthMm: Float, focalLengthMm: Float): Float {
        if (sensorWidthMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorWidthMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Calculates vertical Field of View in degrees from physical sensor height and focal length.
     */
    fun calculateVerticalFovDegrees(sensorHeightMm: Float, focalLengthMm: Float): Float {
        if (sensorHeightMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorHeightMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    const val DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16.0f
    const val DEFAULT_MAIN_EQUIVALENT_FOCAL_MM = 23.0f
    const val MIN_SWITCH_POINT_MM = 23.0f
    const val MAX_SWITCH_POINT_MM = 85.0f
    const val DEFAULT_SWITCH_POINT_MM = 23.0f

    /**
     * Converts a UI zoom level (where 1.0x = 23mm Main lens) to effective focal length in mm.
     * - 0.5x UI zoom -> 16.0mm (native uncropped Ultra-Wide)
     * - 1.0x UI zoom -> 23.0mm (native uncropped Main)
     * - Linear interpolation between 0.5x and 1.0x (16mm to 23mm)
     * - For zoom >= 1.0x -> zoom * 23.0mm
     */
    fun zoomToFocalLengthMm(
        zoom: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (zoom <= 1.0f) {
            val t = ((zoom - 0.5f) / 0.5f).coerceIn(0f, 1f)
            uwFocalMm + t * (mainFocalMm - uwFocalMm)
        } else {
            zoom * mainFocalMm
        }
    }

    /**
     * Converts an effective focal length in mm to UI zoom level.
     */
    fun focalLengthMmToZoom(
        focalMm: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (focalMm <= mainFocalMm) {
            val t = ((focalMm - uwFocalMm) / (mainFocalMm - uwFocalMm)).coerceIn(0f, 1f)
            0.5f + t * 0.5f
        } else {
            focalMm / mainFocalMm
        }
    }

    /**
     * Calculates the exact UI zoom threshold at which the lens switch point occurs.
     */
    fun switchPointToZoom(
        switchPointMm: Float,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clamped = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        return clamped / mainFocalMm
    }

    /**
     * Ultra-wide crop = Switch Point ÷ 16mm
     */
    fun calculateUltraWideCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseUw = if (uwFocalMm > 0f) uwFocalMm else DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseUw) * 100f).roundToInt() / 100f
    }

    /**
     * Calculates the Ultra-Wide crop needed to match the active Main camera at a lens handoff.
     */
    fun calculateFovMatchedUltraWideCrop(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val safeMainFocalMm = mainEquivalentFocalMm
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        val safeUwFocalMm = uwEquivalentFocalMm
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        val safeSwitchPointMm = switchPointMm
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
            ?: DEFAULT_SWITCH_POINT_MM

        val targetEquivalentFocalMm = maxOf(safeSwitchPointMm, safeMainFocalMm)
        return (targetEquivalentFocalMm / safeUwFocalMm).coerceAtLeast(1f)
    }

    /**
     * Calculates the Main camera crop at the configured switch-point focal length.
     */
    fun calculateMainCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseMain = if (mainFocalMm > 0f) mainFocalMm else DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseMain) * 100f).roundToInt() / 100f
    }

    /**
     * Calculates the crop limit required for Ultra-Wide (~16mm equivalent) to reach Main-lens FOV.
     */
    fun calculateUltraWideCropLimit(
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return calculateUltraWideCropForSwitchPoint(mainEquivalentFocalMm, uwEquivalentFocalMm)
    }

    /**
     * Calculates the calibrated optical equivalence zoom ratio of a physical lens relative to the primary 1.0x Main camera.
     */
    fun calculateCalibratedOpticalRatio(
        lensFocalLengthMm: Float,
        lensSensorWidthMm: Float,
        mainFocalLengthMm: Float,
        mainSensorWidthMm: Float,
        lensType: LensType,
        logicalMinZoomRatio: Float? = null
    ): Float {
        if (lensType == LensType.WIDE) return 1.0f

        val hasValidMain = mainSensorWidthMm > 0f && mainFocalLengthMm > 0f
        val hasValidLens = lensSensorWidthMm > 0f && lensFocalLengthMm > 0f

        val fovBasedRatio = if (hasValidMain && hasValidLens) {
            val tanHalfMain = mainSensorWidthMm.toDouble() / (2.0 * mainFocalLengthMm.toDouble())
            val tanHalfLens = lensSensorWidthMm.toDouble() / (2.0 * lensFocalLengthMm.toDouble())
            if (tanHalfLens > 0.0) {
                (tanHalfMain / tanHalfLens).toFloat()
            } else 1.0f
        } else if (mainFocalLengthMm > 0f && lensFocalLengthMm > 0f) {
            lensFocalLengthMm / mainFocalLengthMm
        } else {
            when (lensType) {
                LensType.ULTRAWIDE -> 0.5f
                LensType.TELEPHOTO -> 2.0f
                LensType.TELEPHOTO_3X -> 3.0f
                else -> 1.0f
            }
        }

        return when (lensType) {
            LensType.ULTRAWIDE -> 0.5f
            LensType.TELEPHOTO -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(1.8f)
            }
            LensType.TELEPHOTO_3X -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(2.8f)
            }
            else -> 1.0f
        }
    }

    /**
     * Calculates the required digital crop factor for a given UI zoom level on the active lens.
     */
    fun calculateRequiredDigitalCrop(
        uiZoom: Float,
        lensBaseRatio: Float,
        lensType: LensType,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM
    ): Float {
        val base = if (lensType == LensType.ULTRAWIDE) 0.5f else (if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f)
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val maxUwCrop = calculateUltraWideCropForSwitchPoint(clampedSwitch, uwEquivalentFocalMm)
        val switchZoom = switchPointToZoom(clampedSwitch, mainEquivalentFocalMm)

        return when (lensType) {
            LensType.ULTRAWIDE -> {
                if (uiZoom <= 0.5f) {
                    1.0f
                } else if (uiZoom <= switchZoom) {
                    if (uiZoom >= switchZoom - 0.001f) {
                        maxUwCrop
                    } else {
                        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        val crop = 1.0f + t * (maxUwCrop - 1.0f)
                        crop.coerceIn(1.0f, maxUwCrop)
                    }
                } else {
                    maxUwCrop * (uiZoom / switchZoom)
                }
            }
            LensType.WIDE -> {
                uiZoom.coerceAtLeast(1.0f)
            }
            LensType.TELEPHOTO, LensType.TELEPHOTO_3X -> {
                (uiZoom / base).coerceAtLeast(1.0f)
            }
            LensType.FRONT -> {
                uiZoom.coerceAtLeast(1.0f)
            }
            else -> {
                uiZoom.coerceAtLeast(1.0f)
            }
        }
    }

    fun shouldKeepUltraWideDuringContinuousZoom(
        currentLensType: LensType,
        targetZoom: Float,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean
    ): Boolean {
        if (!isContinuousTransition || currentLensType != LensType.ULTRAWIDE) return false
        val switchZoom = switchPointToZoom(switchPointMm)
        return targetZoom < switchZoom
    }

    /**
     * Hysteresis-aware lens selection during continuous zoom dragging based on user-selected Switch Point.
     * At or above the Switch Point threshold (Switch Point / 23mm): switches to Main (or Telephoto).
     * Strictly below the Switch Point threshold: uses Ultra-wide if available.
     * 0.5x <-> 1.0x transition uses the exact same continuous mapping and boundary in both directions.
     */
    fun resolveTargetLensType(
        currentLensType: LensType,
        targetZoom: Float,
        hasUltraWide: Boolean,
        hasTelephoto2x: Boolean,
        hasTelephoto3x: Boolean,
        isPresetTap: Boolean,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean = false
    ): LensType {
        if (currentLensType == LensType.FRONT) {
            return LensType.FRONT
        }
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val switchZoom = switchPointToZoom(clampedSwitch, DEFAULT_MAIN_EQUIVALENT_FOCAL_MM)

        // At or above the dynamic switch point zoom threshold, target Main (Wide) or Telephoto lens
        if (targetZoom >= switchZoom) {
            val isDirectOrTransition = isPresetTap || isContinuousTransition
            return when {
                targetZoom >= 2.8f && hasTelephoto3x && (isDirectOrTransition || currentLensType == LensType.TELEPHOTO_3X || targetZoom >= 3.15f) && switchZoom <= 2.8f -> LensType.TELEPHOTO_3X
                targetZoom >= 1.8f && hasTelephoto2x && (isDirectOrTransition || currentLensType == LensType.TELEPHOTO || targetZoom >= 2.15f) && switchZoom <= 1.8f -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO && targetZoom >= 1.85f && switchZoom <= 1.85f -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO_3X && targetZoom >= 2.85f && switchZoom <= 2.85f -> LensType.TELEPHOTO_3X
                else -> LensType.WIDE
            }
        }

        // Strictly below the switch point: use Ultra-wide if available
        return if (hasUltraWide) LensType.ULTRAWIDE else LensType.WIDE
    }

    data class OpticalFramingCalibration(
        val scaleCorrection: Float = 1.0f,
        val offsetXNorm: Float = 0.0f,
        val offsetYNorm: Float = 0.0f
    )

    fun getUltraWideFramingCalibration(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): OpticalFramingCalibration {
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f,
            offsetXNorm = 0.0f,
            offsetYNorm = 0.0f
        )
    }

    fun interpolateUltraWideFraming(
        uiZoom: Float,
        calib: OpticalFramingCalibration,
        switchZoom: Float = 1.0f
    ): OpticalFramingCalibration {
        if (uiZoom <= 0.5f) {
            return OpticalFramingCalibration()
        }
        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f,
            offsetXNorm = calib.offsetXNorm * t,
            offsetYNorm = calib.offsetYNorm * t
        )
    }
}
