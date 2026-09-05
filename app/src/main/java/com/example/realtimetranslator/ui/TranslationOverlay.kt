package com.example.realtimetranslator.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.example.realtimetranslator.core.ViewportMapper
import com.example.realtimetranslator.model.OverlayState
import com.example.realtimetranslator.render.TextLayoutCache

/** How far a fill overshoots its line, as a fraction of the line height. */
private const val FILL_PADDING_RATIO = 0.14f

/** Translated text is drawn at this fraction of the original line height. */
private const val CAP_HEIGHT_RATIO = 0.78f

private const val TEXT_INSET_RATIO = 0.08f

/**
 * Paints the translation over the words it replaces.
 *
 * Three things keep this from looking like labels stuck onto the picture. The
 * fill follows each recognized line as a rotated rectangle rather than covering
 * the axis-aligned box around a slanted block; the colours are the surface and
 * ink estimated from the original, not a generic panel; and the text is drawn at
 * the height the original was printed at, along the same slant.
 *
 * The draw pass does no image processing at all - colours were computed when the
 * block was recognized, and the line breaks come from a memoised layout.
 *
 * State arrives as a [State] rather than an already-read value on purpose:
 * reading it inside the draw lambda means the tracker moving boxes thirty times
 * a second invalidates only the draw phase instead of recomposing the screen.
 */
@Composable
fun TranslationOverlay(
    state: State<OverlayState>,
    modifier: Modifier = Modifier
) {
    val layouts = remember { TextLayoutCache() }
    val fill = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
    }
    val ink = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    Canvas(modifier = modifier) {
        val current = state.value
        if (current.blocks.isEmpty() || current.imageWidth <= 0 || current.imageHeight <= 0) {
            return@Canvas
        }

        val mapper =
            ViewportMapper(size.width, size.height, current.imageWidth, current.imageHeight)
        val canvas = drawContext.canvas.nativeCanvas

        current.blocks.forEach { block ->
            var lineHeightTotal = 0f
            var lineCount = 0

            block.lines.forEach { line ->
                val view = mapper.imageToView(line.box)
                if (view.width <= 0f || view.height <= 0f) return@forEach

                lineHeightTotal += view.height
                lineCount++

                // Each line carries the colour sampled behind itself, so a block
                // crossing two surfaces covers each one in its own colour.
                fill.color = line.paper
                val padding = view.height * FILL_PADDING_RATIO
                canvas.save()
                canvas.rotate(view.angleDegrees, view.centerX, view.centerY)
                canvas.drawRect(
                    view.centerX - view.width / 2f - padding,
                    view.centerY - view.height / 2f - padding,
                    view.centerX + view.width / 2f + padding,
                    view.centerY + view.height / 2f + padding,
                    fill
                )
                canvas.restore()
            }

            if (lineCount == 0) return@forEach

            val area = mapper.imageToView(block.area)
            if (area.width <= 0f || area.height <= 0f) return@forEach

            val lineHeight = lineHeightTotal / lineCount
            val inset = lineHeight * TEXT_INSET_RATIO

            val laidOut = layouts.layout(
                text = block.translatedText,
                maxWidth = area.width - 2 * inset,
                maxHeight = area.height,
                preferredSize = lineHeight * CAP_HEIGHT_RATIO
            )
            if (laidOut.lines.isEmpty()) return@forEach

            ink.color = block.ink
            ink.textSize = laidOut.textSize

            val textHeight = laidOut.lines.size * laidOut.textSize * TextLayoutCache.LINE_SPACING

            canvas.save()
            canvas.rotate(area.angleDegrees, area.centerX, area.centerY)

            val left = area.centerX - area.width / 2f + inset
            var baseline = area.centerY - textHeight / 2f + laidOut.textSize
            laidOut.lines.forEach { line ->
                canvas.drawText(line, left, baseline, ink)
                baseline += laidOut.textSize * TextLayoutCache.LINE_SPACING
            }

            canvas.restore()
        }
    }
}
