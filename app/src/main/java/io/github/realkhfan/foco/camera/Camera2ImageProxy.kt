package io.github.realkhfan.foco.camera

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import java.nio.ByteBuffer

/**
 * Bridges native Camera2 [android.media.Image] to CameraX [ImageProxy]
 * for Foco's processing pipeline (burst HDR, Night, Panorama, etc.).
 */
class Camera2ImageProxy(
    private val image: Image,
    private val timestampNs: Long = image.timestamp
) : ImageProxy {

    override fun getFormat(): Int = image.format

    override fun getWidth(): Int = image.width

    override fun getHeight(): Int = image.height

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
        return image.planes.map { plane ->
            object : ImageProxy.PlaneProxy {
                override fun getBuffer(): ByteBuffer = plane.buffer
                override fun getPixelStride(): Int = plane.pixelStride
                override fun getRowStride(): Int = plane.rowStride
            }
        }.toTypedArray()
    }

    override fun getImageInfo(): ImageInfo = object : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
        override fun getTimestamp(): Long = timestampNs
        override fun getRotationDegrees(): Int = 0
        override fun populateExifData(builder: ExifData.Builder) {}
    }

    override fun getCropRect(): Rect = image.cropRect

    override fun setCropRect(rect: Rect?) {
        image.cropRect = rect
    }

    override fun getImage(): Image = image

    override fun close() = image.close()
}
