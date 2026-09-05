package com.example.realtimetranslator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.realtimetranslator.model.Corner
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/** Visual radius of a corner handle. */
private const val HANDLE_RADIUS = 12f

/** Touch radius, deliberately larger than the handle it grabs. */
private const val GRAB_RADIUS = 80f

private const val MIN_SIZE = 80f

/**
 * The draggable region the user points at text with. Dragging inside moves it,
 * dragging a corner resizes it.
 */
@Composable
fun SelectionOverlay(
    selection: Rect?,
    activeCorner: Corner?,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = onDragStart,
                onDrag = { change, delta ->
                    change.consume()
                    onDrag(delta)
                },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd
            )
        }
    ) {
        val rect = selection ?: return@Canvas

        drawRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 3f)
        )

        fun handle(x: Float, y: Float, corner: Corner) {
            drawCircle(
                color = if (activeCorner == corner) Color.Yellow.copy(alpha = 0.7f) else Color.White,
                radius = HANDLE_RADIUS,
                center = Offset(x, y)
            )
        }

        handle(rect.left, rect.top, Corner.TOP_LEFT)
        handle(rect.right, rect.top, Corner.TOP_RIGHT)
        handle(rect.left, rect.bottom, Corner.BOTTOM_LEFT)
        handle(rect.right, rect.bottom, Corner.BOTTOM_RIGHT)
    }
}

/** Which corner, if any, the given touch grabbed. */
fun cornerNear(rect: Rect, offset: Offset): Corner? = when {
    isNear(offset, rect.left, rect.top) -> Corner.TOP_LEFT
    isNear(offset, rect.right, rect.top) -> Corner.TOP_RIGHT
    isNear(offset, rect.left, rect.bottom) -> Corner.BOTTOM_LEFT
    isNear(offset, rect.right, rect.bottom) -> Corner.BOTTOM_RIGHT
    else -> null
}

/**
 * Applies a drag to the selection.
 *
 * The result is always normalised and clamped: dragging a corner past its
 * opposite edge used to leave an inverted rectangle, which then failed every
 * containment test against it.
 */
fun resizeSelection(rect: Rect, corner: Corner?, delta: Offset, bounds: Size): Rect {
    val dragged = when (corner) {
        Corner.TOP_LEFT -> rect.copy(left = rect.left + delta.x, top = rect.top + delta.y)
        Corner.TOP_RIGHT -> rect.copy(right = rect.right + delta.x, top = rect.top + delta.y)
        Corner.BOTTOM_LEFT -> rect.copy(left = rect.left + delta.x, bottom = rect.bottom + delta.y)
        Corner.BOTTOM_RIGHT -> rect.copy(right = rect.right + delta.x, bottom = rect.bottom + delta.y)
        null -> rect.translate(delta.x, delta.y)
    }
    return dragged.normalised().constrainedTo(bounds)
}

private fun isNear(offset: Offset, x: Float, y: Float): Boolean =
    (offset.x - x).absoluteValue < GRAB_RADIUS && (offset.y - y).absoluteValue < GRAB_RADIUS

private fun Rect.normalised(): Rect {
    val left = min(this.left, this.right)
    val right = max(this.left, this.right)
    val top = min(this.top, this.bottom)
    val bottom = max(this.top, this.bottom)
    return Rect(left, top, max(right, left + MIN_SIZE), max(bottom, top + MIN_SIZE))
}

private fun Rect.constrainedTo(bounds: Size): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return this
    val width = min(this.width, bounds.width)
    val height = min(this.height, bounds.height)
    val left = this.left.coerceIn(0f, bounds.width - width)
    val top = this.top.coerceIn(0f, bounds.height - height)
    return Rect(left, top, left + width, top + height)
}
