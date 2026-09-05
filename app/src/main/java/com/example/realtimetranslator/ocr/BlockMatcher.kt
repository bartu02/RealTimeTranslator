package com.example.realtimetranslator.ocr

import android.graphics.RectF
import com.example.realtimetranslator.core.TextHeuristics
import com.example.realtimetranslator.model.OverlayBlock
import com.example.realtimetranslator.model.RecognizedBlock

/**
 * Decides whether a fresh reading is the same text the overlay is already
 * showing.
 *
 * Without this every recognizer pass rebuilds the overlay from nothing, so the
 * translation visibly changes several times a second even when neither the
 * camera nor the words have moved. Matching a reading against what is already on
 * screen lets a translation stay put once it is right - which is the difference
 * between an overlay that flickers and one that looks printed on the surface.
 */
object BlockMatcher {

    /** Boxes must overlap at least this much to be candidates for the same text. */
    private const val MIN_OVERLAP = 0.35f

    /** ...and read closely enough that only the recognizer wobbled. */
    private const val MIN_TEXT_SIMILARITY = 0.75

    /** Intersection over union of two axis-aligned boxes. */
    fun overlap(a: RectF, b: RectF): Float =
        overlap(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

    /**
     * The same measure over plain coordinates.
     *
     * `RectF` is a stub in local unit tests, so the arithmetic lives here where
     * it can actually be covered.
     */
    fun overlap(
        aLeft: Float,
        aTop: Float,
        aRight: Float,
        aBottom: Float,
        bLeft: Float,
        bTop: Float,
        bRight: Float,
        bBottom: Float
    ): Float {
        val left = maxOf(aLeft, bLeft)
        val top = maxOf(aTop, bTop)
        val right = minOf(aRight, bRight)
        val bottom = minOf(aBottom, bBottom)
        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val areaA = (aRight - aLeft) * (aBottom - aTop)
        val areaB = (bRight - bLeft) * (bBottom - bTop)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /**
     * The block in [candidates] that best explains [incoming], or null when this
     * is text the overlay has not seen before.
     */
    fun bestMatch(candidates: List<OverlayBlock>, incoming: RecognizedBlock): OverlayBlock? {
        var best: OverlayBlock? = null
        var bestScore = MIN_OVERLAP
        for (candidate in candidates) {
            val score = overlap(candidate.box, incoming.box)
            if (score >= bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /**
     * The same search over readings that are waiting to be confirmed rather than
     * blocks already on screen.
     */
    fun bestPending(
        candidates: List<RecognizedBlock>,
        incoming: RecognizedBlock
    ): RecognizedBlock? {
        var best: RecognizedBlock? = null
        var bestScore = MIN_OVERLAP
        for (candidate in candidates) {
            val score = overlap(candidate.box, incoming.box)
            if (score >= bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /**
     * Whether two readings are the same words, allowing for the recognizer
     * wobbling a character or two between passes.
     */
    fun readsTheSame(one: String, other: String): Boolean =
        TextHeuristics.similarity(
            TextHeuristics.normalize(one),
            TextHeuristics.normalize(other)
        ) >= MIN_TEXT_SIMILARITY
}
