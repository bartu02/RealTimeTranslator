package com.example.realtimetranslator.camera

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

/**
 * A downscaled, upright grayscale view of one camera frame.
 *
 * [scaleFromUpright] maps full upright image coordinates into this Mat, so a
 * translation measured here must be divided by it before it is applied to
 * overlay boxes.
 *
 * The Mat belongs to the [FrameConverter] that produced it and stays valid only
 * until the next call - do not release it, and do not hold it across frames.
 */
class GrayFrame(val mat: Mat, val scaleFromUpright: Float)

/**
 * Produces tracking images without allocating anything after the first frame.
 *
 * Rather than copying the full luminance plane and asking OpenCV to resize it,
 * this walks the plane at a stride and averages each 2x2 block on the way past.
 * For a 1280x720 frame reduced to 320 that reads about half the plane instead of
 * all of it, skips the resize entirely, and - because every buffer is reused -
 * adds nothing to the garbage collector at 30 frames per second.
 */
class FrameConverter {

    private var luma = ByteArray(0)
    private var topRow = ByteArray(0)
    private var bottomRow = ByteArray(0)

    private val sampled = Mat()
    private val upright = Mat()

    fun grayFrame(image: ImageProxy, longSide: Int): GrayFrame? {
        val plane = image.planes.getOrNull(0) ?: return null
        val sourceWidth = image.width
        val sourceHeight = image.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val step = maxOf(1, maxOf(sourceWidth, sourceHeight) / longSide)
        val width = sourceWidth / step
        val height = sourceHeight / step
        if (width <= 0 || height <= 0) return null

        val planeRowStride = plane.rowStride
        if (luma.size != width * height) luma = ByteArray(width * height)
        if (topRow.size != planeRowStride) {
            topRow = ByteArray(planeRowStride)
            bottomRow = ByteArray(planeRowStride)
        }

        try {
            downsample(
                buffer = plane.buffer.duplicate(),
                rowStride = planeRowStride,
                pixelStride = plane.pixelStride,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                step = step,
                width = width,
                height = height
            )
        } catch (throwable: Throwable) {
            return null
        }

        if (sampled.rows() != height || sampled.cols() != width) {
            sampled.create(height, width, CvType.CV_8UC1)
        }
        sampled.put(0, 0, luma)

        val oriented = orient(sampled, upright, image.imageInfo.rotationDegrees)
        return GrayFrame(oriented, width.toFloat() / sourceWidth)
    }

    fun release() {
        sampled.release()
        upright.release()
    }

    private fun downsample(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        step: Int,
        width: Int,
        height: Int
    ) {
        val limit = buffer.limit()
        val neededPerRow = (width - 1) * step * pixelStride + pixelStride + 1
        var target = 0

        for (row in 0 until height) {
            val firstRow = row * step
            val secondRow = minOf(firstRow + 1, sourceHeight - 1)

            val topCount = readRow(buffer, firstRow * rowStride, topRow, limit)
            val bottomCount = readRow(buffer, secondRow * rowStride, bottomRow, limit)

            if (topCount >= neededPerRow && bottomCount >= neededPerRow) {
                // The common case: every sample the row needs is present.
                var source = 0
                for (column in 0 until width) {
                    val sum = (topRow[source].toInt() and 0xFF) +
                        (topRow[source + pixelStride].toInt() and 0xFF) +
                        (bottomRow[source].toInt() and 0xFF) +
                        (bottomRow[source + pixelStride].toInt() and 0xFF)
                    luma[target++] = (sum shr 2).toByte()
                    source += step * pixelStride
                }
            } else {
                for (column in 0 until width) {
                    val first = column * step * pixelStride
                    val second = minOf(first + pixelStride, (sourceWidth - 1) * pixelStride)
                    var sum = 0
                    var samples = 0
                    if (first < topCount) { sum += topRow[first].toInt() and 0xFF; samples++ }
                    if (second < topCount) { sum += topRow[second].toInt() and 0xFF; samples++ }
                    if (first < bottomCount) { sum += bottomRow[first].toInt() and 0xFF; samples++ }
                    if (second < bottomCount) { sum += bottomRow[second].toInt() and 0xFF; samples++ }
                    luma[target++] = if (samples == 0) 0 else (sum / samples).toByte()
                }
            }
        }
    }

    private fun readRow(buffer: ByteBuffer, offset: Int, into: ByteArray, limit: Int): Int {
        if (offset >= limit) return 0
        val available = minOf(into.size, limit - offset)
        buffer.position(offset)
        buffer.get(into, 0, available)
        return available
    }

    private fun orient(source: Mat, target: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return source
        }
        Core.rotate(source, target, code)
        return target
    }
}
