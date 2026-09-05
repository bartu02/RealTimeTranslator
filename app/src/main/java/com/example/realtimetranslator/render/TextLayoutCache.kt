package com.example.realtimetranslator.render

import android.graphics.Paint

/** Text already wrapped to fit inside a box. */
data class LaidOutText(val lines: List<String>, val textSize: Float)

/**
 * Wraps translated text into a box.
 *
 * The preferred size is tried first, and only if it does not fit is a smaller
 * one searched for. That ordering matters visually: the caller passes the height
 * of the words being replaced, so the translation comes out at the size the
 * original was printed at rather than stretched to fill its box.
 *
 * The search itself is a bisection instead of a walk down one pixel at a time,
 * and results are memoised - tracking moves boxes constantly but changes their
 * size only slowly, so the quantised key hits on almost every frame.
 *
 * Not thread safe: it owns a mutable [Paint] and is meant for the UI thread.
 */
class TextLayoutCache(private val capacity: Int = 64) {

    private val paint = Paint().apply { isAntiAlias = true }

    private val cache = object : LinkedHashMap<Key, LaidOutText>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, LaidOutText>?): Boolean =
            size > capacity
    }

    fun layout(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        preferredSize: Float
    ): LaidOutText {
        if (text.isEmpty() || maxWidth <= 0f || maxHeight <= 0f) return EMPTY

        val key = Key(text, quantise(maxWidth), quantise(maxHeight), quantise(preferredSize))
        cache[key]?.let { return it }

        val laidOut = compute(text, maxWidth, maxHeight, preferredSize)
        cache[key] = laidOut
        return laidOut
    }

    private fun compute(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        preferredSize: Float
    ): LaidOutText {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return EMPTY

        val preferred = preferredSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        val atPreferred = wrap(words, preferred, maxWidth)
        if (fits(atPreferred.size, preferred, maxHeight)) {
            return LaidOutText(atPreferred, preferred)
        }

        var low = MIN_TEXT_SIZE
        var high = preferred
        var best: LaidOutText? = null

        repeat(SEARCH_STEPS) {
            val candidate = (low + high) / 2f
            val lines = wrap(words, candidate, maxWidth)
            if (fits(lines.size, candidate, maxHeight)) {
                best = LaidOutText(lines, candidate)
                low = candidate
            } else {
                high = candidate
            }
        }

        best?.let { return it }

        // Even the smallest size overflows: clip to the lines that do fit.
        val lines = wrap(words, MIN_TEXT_SIZE, maxWidth)
        val maxLines = (maxHeight / (MIN_TEXT_SIZE * LINE_SPACING)).toInt().coerceAtLeast(1)
        return LaidOutText(clip(lines, maxLines), MIN_TEXT_SIZE)
    }

    private fun fits(lineCount: Int, textSize: Float, maxHeight: Float): Boolean =
        lineCount * textSize * LINE_SPACING <= maxHeight

    private fun wrap(words: List<String>, textSize: Float, maxWidth: Float): List<String> {
        paint.textSize = textSize

        val lines = ArrayList<String>()
        val line = StringBuilder()

        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            // A single word wider than the box still has to go somewhere.
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line.setLength(0)
                line.append(candidate)
            } else {
                lines.add(line.toString())
                line.setLength(0)
                line.append(word)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    private fun clip(lines: List<String>, maxLines: Int): List<String> = when {
        lines.size <= maxLines -> lines
        maxLines <= 1 -> listOf(ELLIPSIS)
        else -> lines.take(maxLines - 1) + ELLIPSIS
    }

    private fun quantise(value: Float): Int = (value / QUANTISATION).toInt()

    private data class Key(
        val text: String,
        val width: Int,
        val height: Int,
        val preferred: Int
    )

    companion object {
        const val LINE_SPACING = 1.15f

        private val WHITESPACE = Regex("""\s+""")
        private val EMPTY = LaidOutText(emptyList(), MIN_TEXT_SIZE)

        private const val MIN_TEXT_SIZE = 10f
        private const val MAX_TEXT_SIZE = 160f
        private const val SEARCH_STEPS = 7
        private const val QUANTISATION = 4f
        private const val ELLIPSIS = "..."
    }
}
