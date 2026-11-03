package io.github.realkhfan.foco

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import io.github.realkhfan.foco.camera.lens.CameraDiscovery
import io.github.realkhfan.foco.camera.lens.CameraInventory
import io.github.realkhfan.foco.camera.lens.CameraOpticalCalibration
import io.github.realkhfan.foco.camera.lens.DualStreamingMode
import io.github.realkhfan.foco.camera.lens.LensInfo
import io.github.realkhfan.foco.camera.lens.LensSwitchStrategy
import io.github.realkhfan.foco.camera.lens.LensType
import io.github.realkhfan.foco.camera.lens.PreviewStreamSource
import io.github.realkhfan.foco.camera.lens.ZoomTransitionController
import io.github.realkhfan.foco.camera.lens.normalizedToZoomLog
import io.github.realkhfan.foco.camera.lens.zoomToNormalizedLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verification unit tests for the ported Lens Detection, Lens Switching & Zoom System.
 */
class PortedLensAndZoomSystemTest {

    @Test
    fun testOpticalFovCalculation() {
        // Main sensor ~6.4mm wide, 4.5mm focal length
        val hFov = CameraOpticalCalibration.calculateHorizontalFovDegrees(6.4f, 4.5f)
        assertTrue("Main horizontal FOV should be around 70-80 degrees", hFov in 65f..85f)

        // Ultra-wide sensor ~5.0mm wide, 2.0mm focal length
        val uwFov = CameraOpticalCalibration.calculateHorizontalFovDegrees(5.0f, 2.0f)
        assertTrue("Ultra-wide horizontal FOV should be > 90 degrees", uwFov > 90f)
    }

    @Test
    fun testCalibratedOpticalRatio() {
        val uwRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
            lensFocalLengthMm = 2.0f,
            lensSensorWidthMm = 5.0f,
            mainFocalLengthMm = 5.0f,
            mainSensorWidthMm = 6.4f,
            lensType = LensType.ULTRAWIDE
        )
        assertEquals(0.5f, uwRatio, 0.001f)

        val teleRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
            lensFocalLengthMm = 12.0f,
            lensSensorWidthMm = 4.0f,
            mainFocalLengthMm = 5.0f,
            mainSensorWidthMm = 6.4f,
            lensType = LensType.TELEPHOTO_3X
        )
        assertTrue("Telephoto 3X optical ratio should be >= 2.8x", teleRatio >= 2.8f)
    }

    @Test
    fun testLensSwitchStrategyResolution() {
        val mainLens = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.WIDE,
            displayName = "1x Main",
            focalLengthMm = 5.0f,
            maxAperture = 1.8f,
            isLogicalMultiCamera = true,
            isPrimaryMain = true
        )

        val physicalUw = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.ULTRAWIDE,
            displayName = "0.5x Ultra Wide",
            focalLengthMm = 2.0f,
            maxAperture = 2.2f,
            physicalCameraId = "2",
            isLogicalMultiCamera = true,
            supportsPhysicalStream = true
        )

        val frontLens = LensInfo(
            cameraId = "1",
            facing = CameraCharacteristics.LENS_FACING_FRONT,
            lensType = LensType.FRONT,
            displayName = "Front Selfie",
            focalLengthMm = 3.5f,
            maxAperture = 2.0f
        )

        val auxTele = LensInfo(
            cameraId = "3",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.TELEPHOTO,
            displayName = "2x Tele",
            focalLengthMm = 10.0f,
            maxAperture = 2.4f,
            isHiddenAux = true,
            isIndependentCamera = true
        )

        // Logical multi-cam physical stream
        assertEquals(
            LensSwitchStrategy.LOGICAL_PHYSICAL_STREAM,
            CameraDiscovery.resolveSwitchStrategy(mainLens, physicalUw)
        )

        // Different camera device (Back to Front)
        assertEquals(
            LensSwitchStrategy.INDEPENDENT_DEVICE,
            CameraDiscovery.resolveSwitchStrategy(mainLens, frontLens)
        )

        // Independent aux camera
        assertEquals(
            LensSwitchStrategy.INDEPENDENT_DEVICE,
            CameraDiscovery.resolveSwitchStrategy(mainLens, auxTele)
        )

        // Same camera logical zoom
        val sameLogical = mainLens.copy(isPrimaryMain = false)
        assertEquals(
            LensSwitchStrategy.LOGICAL_ZOOM,
            CameraDiscovery.resolveSwitchStrategy(mainLens, sameLogical)
        )
    }

    @Test
    fun testHysteresisAwareLensSelection() {
        // Below switch point (e.g. 0.7x) with ultrawide available -> ULTRAWIDE
        val lensBelow1 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.WIDE,
            targetZoom = 0.7f,
            hasUltraWide = true,
            hasTelephoto2x = true,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
        )
        assertEquals(LensType.ULTRAWIDE, lensBelow1)

        // At 1.0x -> WIDE
        val lensAt1 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.0f,
            hasUltraWide = true,
            hasTelephoto2x = true,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
        )
        assertEquals(LensType.WIDE, lensAt1)

        // At 2.5x with 2x telephoto -> TELEPHOTO
        val lensAt25 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.WIDE,
            targetZoom = 2.5f,
            hasUltraWide = true,
            hasTelephoto2x = true,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
        )
        assertEquals(LensType.TELEPHOTO, lensAt25)
    }

    @Test
    fun testDirectTelephotoHandoffNoOneXPause() {
        // Direct 0.5x -> 2.0x / 3.0x must be identified as telephoto preset transition
        assertTrue(ZoomTransitionController.isHalfXToTelephotoPresetTransition(0.5f, 2.0f))
        assertTrue(ZoomTransitionController.isHalfXToTelephotoPresetTransition(0.5f, 3.0f))
        assertTrue(ZoomTransitionController.isTelephotoPresetToHalfXTransition(2.0f, 0.5f))
        assertTrue(ZoomTransitionController.isTelephotoPresetToHalfXTransition(3.0f, 0.5f))

        // Direct 0.5x -> 2.0x must NOT use hold-time handoff (which is reserved for 0.5x -> 1.0x)
        assertFalse(ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 2.0f))
        assertTrue(ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 1.0f))

        // Direct 0.5x -> 2.0x must be eligible for frame-based handoff
        assertTrue(ZoomTransitionController.isFrameBasedHandoffEligible(0.5f, 2.0f))
        assertFalse(ZoomTransitionController.isFrameBasedHandoffEligible(0.5f, 1.0f))
    }

    @Test
    fun testTransitionTimingConstants() {
        assertEquals(400L, ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS)
        assertEquals(300L, ZoomTransitionController.VIDEO_TRANSITION_DURATION_MS)
        assertEquals(200L, ZoomTransitionController.DEFAULT_HOLD_AT_ONE_X_DURATION_MS)
        assertEquals(16L, ZoomTransitionController.ZOOM_UPDATE_INTERVAL_MS)
    }

    @Test
    fun testLogarithmicZoomScaleMapping() {
        val minZ = 0.5f
        val maxZ = 20.0f

        val normAtMin = zoomToNormalizedLog(minZ, minZ, maxZ)
        assertEquals(0f, normAtMin, 0.001f)

        val normAtMax = zoomToNormalizedLog(maxZ, minZ, maxZ)
        assertEquals(1f, normAtMax, 0.001f)

        val zoomBackMin = normalizedToZoomLog(0f, minZ, maxZ)
        assertEquals(minZ, zoomBackMin, 0.01f)

        val zoomBackMax = normalizedToZoomLog(1f, minZ, maxZ)
        assertEquals(maxZ, zoomBackMax, 0.01f)

        // Monotonic progression
        var previousZoom = minZ
        for (step in 1..20) {
            val progress = step / 20f
            val z = normalizedToZoomLog(progress, minZ, maxZ)
            assertTrue("Zoom must increase monotonically", z > previousZoom)
            previousZoom = z
        }
    }

    @Test
    fun testRequiredDigitalCropContinuousMapping() {
        // At 0.5x on Ultra-Wide -> exactly 1.0x uncropped
        val cropAtHalf = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 0.5f,
            lensBaseRatio = 0.5f,
            lensType = LensType.ULTRAWIDE
        )
        assertEquals(1.0f, cropAtHalf, 0.001f)

        // At 1.0x on Ultra-Wide -> crop limit matching 1x Main FOV (~1.44x)
        val cropAtOne = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 1.0f,
            lensBaseRatio = 0.5f,
            lensType = LensType.ULTRAWIDE
        )
        assertTrue("Ultra-Wide crop at 1.0x should match ~1.44x", cropAtOne in 1.40f..1.48f)

        // On Main at 1.0x -> 1.0x
        val cropMain1 = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 1.0f,
            lensBaseRatio = 1.0f,
            lensType = LensType.WIDE
        )
        assertEquals(1.0f, cropMain1, 0.001f)

        // On Main at 2.0x -> 2.0x
        val cropMain2 = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 2.0f,
            lensBaseRatio = 1.0f,
            lensType = LensType.WIDE
        )
        assertEquals(2.0f, cropMain2, 0.001f)
    }

    @Test
    fun testFrameHandoffAlphaBlendingContinuity() {
        // 0 incoming frames -> progress = 0
        val p0 = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 0,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.0f,
            previousProgress = 0f
        )
        assertEquals(0f, p0, 0.01f)

        // 16 incoming frames -> progress = 1.0
        val p16 = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 16,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.0f,
            previousProgress = 0.8f
        )
        assertEquals(1f, p16, 0.01f)
    }
}
