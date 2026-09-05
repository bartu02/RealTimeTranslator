package com.example.realtimetranslator.core

import android.graphics.RectF
import com.example.realtimetranslator.model.OrientedBox

/**
 * Converts between upright image coordinates and view coordinates for a
 * `PreviewView` using `ScaleType.FIT_CENTER`.
 *
 * The preview letterboxes the frame, so exactly one axis is fully used and the
 * other is padded by [offsetX] / [offsetY].
 */
class ViewportMapper(
    private val viewWidth: Float,
    private val viewHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val scale: Float
    val offsetX: Float
    val offsetY: Float

    init {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            val viewAspect = viewWidth / viewHeight
            val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
            if (viewAspect > imageAspect) {
                scale = viewHeight / imageHeight
                offsetX = (viewWidth - imageWidth * scale) / 2f
                offsetY = 0f
            } else {
                scale = viewWidth / imageWidth
                offsetX = 0f
                offsetY = (viewHeight - imageHeight * scale) / 2f
            }
        }
    }

    fun imageToView(box: RectF): RectF = RectF(
        offsetX + box.left * scale,
        offsetY + box.top * scale,
        offsetX + box.right * scale,
        offsetY + box.bottom * scale
    )

    /** Uniform scaling leaves the angle alone. */
    fun imageToView(box: OrientedBox): OrientedBox = OrientedBox(
        centerX = offsetX + box.centerX * scale,
        centerY = offsetY + box.centerY * scale,
        width = box.width * scale,
        height = box.height * scale,
        angleDegrees = box.angleDegrees
    )

    fun viewToImage(box: RectF): RectF = RectF(
        (box.left - offsetX) / scale,
        (box.top - offsetY) / scale,
        (box.right - offsetX) / scale,
        (box.bottom - offsetY) / scale
    )
}
