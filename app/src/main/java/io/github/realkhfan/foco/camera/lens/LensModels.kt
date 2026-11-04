package io.github.realkhfan.foco.camera.lens

import android.hardware.camera2.CameraCharacteristics
import java.util.UUID

enum class LensType(val shortLabel: String, val fullLabel: String) {
    ULTRAWIDE("0.5x", "Ultra Wide"),
    WIDE("1x", "Main Wide"),
    TELEPHOTO("2x", "2x Telephoto"),
    TELEPHOTO_3X("3x", "3x Telephoto"),
    MACRO("Macro", "Macro Lens"),
    FRONT("1x", "Front Selfie")
}

data class LensInfo(
    val cameraId: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_BACK, etc.
    val lensType: LensType,
    val displayName: String,
    val focalLengthMm: Float,
    val maxAperture: Float,
    val isPhysical: Boolean = false,
    val isHiddenAux: Boolean = false,
    val isZoomPreset: Boolean = false,
    val baseZoomRatio: Float = 1.0f,
    val physicalCameraId: String? = null,
    val fovDegrees: Float = 0f,
    val equivalent35mmFocalMm: Float = 0f,
    val idTypeDescription: String = "Logical",
    val isLogicalMultiCamera: Boolean = false,
    val minZoomRatio: Float = 0.5f,
    val maxZoomRatio: Float = 20.0f,
    val isPrimaryMain: Boolean = false,
    val isIndependentCamera: Boolean = false,
    val supportsPhysicalStream: Boolean = false,
    val intrinsicZoomRatio: Float = baseZoomRatio,
    val id: String = "${cameraId}_${physicalCameraId.orEmpty()}_${lensType.name}"
)

enum class LensSwitchStrategy {
    INDEPENDENT_DEVICE,
    LOGICAL_PHYSICAL_STREAM,
    LOGICAL_ZOOM
}

data class CameraInventory(
    val lenses: List<LensInfo>,
    val logicalToPhysicalMap: Map<String, Set<String>> = emptyMap(),
    val physicalToLogicalMap: Map<String, String> = emptyMap(),
    val characteristicsCache: Map<String, CameraCharacteristics> = emptyMap()
)

enum class BackgroundCameraStatus(val label: String, val shortDesc: String) {
    OFF("Off", "Standby Disabled"),
    PREPARING("Preparing", "Starting background stream..."),
    READY_QUIET("Ready (Quiet)", "Ready in background (invisible)"),
    READY_PREVIEW("Live Preview", "Streaming to preview"),
    UNAVAILABLE("Unavailable", "Camera not present or in use")
}

enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

data class PreviewOverlapState(
    val isOverlapping: Boolean = false,
    val mainAlpha: Float = 1.0f,
    val ultraWideAlpha: Float = 0.0f,
    val activeSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val overlapProgress: Float = 0.0f,
    val topSource: PreviewStreamSource = PreviewStreamSource.MAIN
)

enum class DualStreamingMode {
    NONE,
    CONCURRENT_DEVICES,
    LOGICAL_MULTI_CAMERA_PHYSICAL,
    PERSISTENT_DUAL_SESSION
}
