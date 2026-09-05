package com.example.realtimetranslator.ocr

import android.graphics.RectF
import com.example.realtimetranslator.model.OrientedBox
import com.example.realtimetranslator.model.RecognizedBlock
import com.google.mlkit.vision.text.Text
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a recognizer result into merged, reading-ordered blocks expressed in
 * upright image space.
 *
 * Each block carries the oriented boxes of its individual lines. Those are what
 * the overlay fills, so the paint hugs the words instead of covering the
 * axis-aligned rectangle around them.
 */
object BlockExtractor {

    /** Two boxes count as one line when they overlap vertically by this much. */
    private const val VERTICAL_OVERLAP_RATIO = 0.5f

    /** ...and are no further apart than this multiple of their height. */
    private const val HORIZONTAL_GAP_RATIO = 1.5f

    /**
     * [originX] / [originY] offset the recognizer's coordinates back into the
     * full frame, because recognition runs on a crop of the selected region.
     */
    fun extract(result: Text, originX: Float, originY: Float): List<RecognizedBlock> {
        val blocks = result.textBlocks
            .mapNotNull { block -> toRecognized(block, originX, originY) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))

        return mergeAdjacent(blocks)
    }

    private fun toRecognized(
        block: Text.TextBlock,
        originX: Float,
        originY: Float
    ): RecognizedBlock? {
        val text = block.text.replace('\n', ' ').trim()
        if (text.isEmpty()) return null

        val lines = block.lines.mapNotNull { line -> toOrientedBox(line, originX, originY) }
        if (lines.isNotEmpty()) {
            return RecognizedBlock(boundsOf(lines), orientedBounds(lines), lines, text)
        }

        // No usable line geometry: fall back to the axis-aligned block bounds.
        val bounds = block.boundingBox ?: return null
        val box = RectF(bounds).apply { offset(originX, originY) }
        if (box.width() <= 0f || box.height() <= 0f) return null
        val area = OrientedBox(box.centerX(), box.centerY(), box.width(), box.height(), 0f)
        return RecognizedBlock(box, area, listOf(area), text)
    }

    private fun toOrientedBox(line: Text.Line, originX: Float, originY: Float): OrientedBox? {
        val corners = line.cornerPoints
        if (corners == null || corners.size < 4) {
            val bounds = line.boundingBox ?: return null
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            return OrientedBox(
                centerX = bounds.exactCenterX() + originX,
                centerY = bounds.exactCenterY() + originY,
                width = bounds.width().toFloat(),
                height = bounds.height().toFloat(),
                angleDegrees = line.angle
            )
        }

        // ML Kit reports the four corners clockwise from the top left of the line.
        val topLeft = corners[0]
        val topRight = corners[1]
        val bottomRight = corners[2]

        val width = hypot(
            (topRight.x - topLeft.x).toDouble(),
            (topRight.y - topLeft.y).toDouble()
        ).toFloat()
        val height = hypot(
            (bottomRight.x - topRight.x).toDouble(),
            (bottomRight.y - topRight.y).toDouble()
        ).toFloat()
        if (width <= 0f || height <= 0f) return null

        var centerX = 0f
        var centerY = 0f
        for (corner in corners) {
            centerX += corner.x
            centerY += corner.y
        }

        return OrientedBox(
            centerX = centerX / corners.size + originX,
            centerY = centerY / corners.size + originY,
            width = width,
            height = height,
            angleDegrees = line.angle
        )
    }

    /**
     * Joins boxes that sit on the same line and close together.
     *
     * Sorting first matters: the recognizer makes no promise about block order,
     * so comparing each block only against the previous one is only meaningful
     * once they are in reading order. Merging also gives the translator a whole
     * phrase rather than two fragments, which it handles far better.
     */
    private fun mergeAdjacent(blocks: List<RecognizedBlock>): List<RecognizedBlock> {
        val merged = ArrayList<RecognizedBlock>(blocks.size)
        for (block in blocks) {
            val last = merged.lastOrNull()
            if (last != null && onSameLine(last.box, block.box) && closeEnough(last.box, block.box)) {
                val lines = last.lines + block.lines
                merged[merged.lastIndex] = RecognizedBlock(
                    box = boundsOf(lines),
                    area = orientedBounds(lines),
                    lines = lines,
                    text = "${last.text} ${block.text}"
                )
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    private fun onSameLine(a: RectF, b: RectF): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0f) return false
        val reference = minOf(a.height(), b.height())
        return reference > 0f && overlap >= VERTICAL_OVERLAP_RATIO * reference
    }

    private fun closeEnough(a: RectF, b: RectF): Boolean {
        val gap = b.left - a.right
        val reference = (a.height() + b.height()) / 2f
        return gap <= HORIZONTAL_GAP_RATIO * reference
    }

    private fun boundsOf(lines: List<OrientedBox>): RectF {
        val corners = FloatArray(8)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        for (line in lines) {
            cornersOf(line, corners)
            for (index in 0 until 4) {
                val x = corners[index * 2]
                val y = corners[index * 2 + 1]
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return RectF(left, top, right, bottom)
    }

    /**
     * The tightest rotated rectangle around every line, taken at the median line
     * angle. Text is laid out inside this, so it follows the same slant as the
     * words it replaces.
     */
    private fun orientedBounds(lines: List<OrientedBox>): OrientedBox {
        val angle = medianAngle(lines)
        val into = Math.toRadians(-angle.toDouble())
        val cosInto = cos(into).toFloat()
        val sinInto = sin(into).toFloat()

        val corners = FloatArray(8)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (line in lines) {
            cornersOf(line, corners)
            for (index in 0 until 4) {
                val x = corners[index * 2]
                val y = corners[index * 2 + 1]
                val rotatedX = x * cosInto - y * sinInto
                val rotatedY = x * sinInto + y * cosInto
                minX = minOf(minX, rotatedX)
                maxX = maxOf(maxX, rotatedX)
                minY = minOf(minY, rotatedY)
                maxY = maxOf(maxY, rotatedY)
            }
        }

        // Turn the centre of those un-rotated bounds back into image space.
        val back = Math.toRadians(angle.toDouble())
        val cosBack = cos(back).toFloat()
        val sinBack = sin(back).toFloat()
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        return OrientedBox(
            centerX = centerX * cosBack - centerY * sinBack,
            centerY = centerX * sinBack + centerY * cosBack,
            width = maxX - minX,
            height = maxY - minY,
            angleDegrees = angle
        )
    }

    private fun medianAngle(lines: List<OrientedBox>): Float {
        if (lines.isEmpty()) return 0f
        val angles = lines.map { it.angleDegrees }.sorted()
        return angles[angles.size / 2]
    }

    /** Writes the four corners of [box] into [out] as x, y pairs. */
    private fun cornersOf(box: OrientedBox, out: FloatArray) {
        val radians = Math.toRadians(box.angleDegrees.toDouble())
        val cosAngle = cos(radians).toFloat()
        val sinAngle = sin(radians).toFloat()
        val halfWidth = box.width / 2f
        val halfHeight = box.height / 2f

        var index = 0
        for (corner in 0 until 4) {
            val localX = if (corner == 0 || corner == 3) -halfWidth else halfWidth
            val localY = if (corner < 2) -halfHeight else halfHeight
            out[index++] = box.centerX + localX * cosAngle - localY * sinAngle
            out[index++] = box.centerY + localX * sinAngle + localY * cosAngle
        }
    }
}
