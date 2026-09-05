package com.example.realtimetranslator.model

import android.graphics.RectF

/**
 * A rotated rectangle.
 *
 * Recognized text is rarely axis aligned, and an axis-aligned box drawn around
 * slanted text is much larger than the text itself. That gap is most of what
 * makes an overlay read as a rectangle pasted onto the scene, so fills and text
 * are both placed with these instead.
 */
data class OrientedBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleDegrees: Float
) {
    val area: Float get() = width * height

    /** The axis-aligned rectangle that contains this box, for sampling and matching. */
    fun bounds(): RectF {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val cosAngle = kotlin.math.cos(radians).toFloat()
        val sinAngle = kotlin.math.sin(radians).toFloat()
        val halfWidth = width / 2f
        val halfHeight = height / 2f

        // Half-extents of the rotated rectangle along each axis.
        val extentX = kotlin.math.abs(halfWidth * cosAngle) + kotlin.math.abs(halfHeight * sinAngle)
        val extentY = kotlin.math.abs(halfWidth * sinAngle) + kotlin.math.abs(halfHeight * cosAngle)

        return RectF(
            centerX - extentX,
            centerY - extentY,
            centerX + extentX,
            centerY + extentY
        )
    }
}

/**
 * One line of a block, together with the surface colour sampled behind *that*
 * line.
 *
 * Sampling per line rather than per block matters whenever a block spans more
 * than one surface - a label that runs from a white panel onto a coloured band,
 * say. A single colour averaged over the whole block matches neither, and the
 * mismatch is exactly what makes the fill read as a rectangle.
 */
data class OverlayLine(
    val box: OrientedBox,
    val paper: Int
)

/**
 * A block of text as it came out of the recognizer.
 *
 * [box] is the axis-aligned bound, kept because matching and colour sampling are
 * cheaper against it. [area] and [lines] carry the real orientation.
 *
 * All coordinates are in *upright image space*: the frame after sensor rotation
 * has been applied, origin at its top-left. Every stage of the pipeline works in
 * this space so boxes stay comparable across frames and recognizer passes.
 */
data class RecognizedBlock(
    val box: RectF,
    val area: OrientedBox,
    val lines: List<OrientedBox>,
    val text: String
)

/**
 * The two colours a block is painted with, both estimated from the original.
 *
 * Using the real ink colour rather than plain black or white is a large part of
 * why a replaced word can look like it was printed there rather than stuck on
 * top of it.
 */
data class BlockStyle(
    val paper: Int,
    val ink: Int
)

/**
 * A translated block ready to draw.
 *
 * [id] is what lets a translation survive across recognizer passes: a new
 * reading that lands on the same words in the same place reuses this block
 * instead of replacing it, so the text on screen stops changing under the user.
 *
 * [missCount] counts consecutive passes that failed to find this block, so one
 * momentary recognizer miss does not make the overlay blink out.
 */
data class OverlayBlock(
    val id: Long,
    val box: RectF,
    val area: OrientedBox,
    val lines: List<OverlayLine>,
    val sourceText: String,
    val translatedText: String,
    val ink: Int,
    val missCount: Int = 0
)

/** Everything the overlay needs in order to draw itself. */
data class OverlayState(
    val blocks: List<OverlayBlock> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val isModelReady: Boolean = false
)

enum class LensMode { ONLINE, OFFLINE }

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
