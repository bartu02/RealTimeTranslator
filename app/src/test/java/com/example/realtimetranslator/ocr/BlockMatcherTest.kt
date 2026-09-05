package com.example.realtimetranslator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockMatcherTest {

    @Test
    fun `identical boxes overlap completely`() {
        assertEquals(1f, overlap(0f, 0f, 10f, 10f, 0f, 0f, 10f, 10f), TOLERANCE)
    }

    @Test
    fun `disjoint boxes do not overlap`() {
        assertEquals(0f, overlap(0f, 0f, 10f, 10f, 20f, 20f, 30f, 30f), TOLERANCE)
    }

    @Test
    fun `boxes that only touch do not overlap`() {
        assertEquals(0f, overlap(0f, 0f, 10f, 10f, 10f, 0f, 20f, 10f), TOLERANCE)
    }

    @Test
    fun `half covered box scores one third`() {
        // Intersection 50, union 100 + 100 - 50 = 150.
        assertEquals(1f / 3f, overlap(0f, 0f, 10f, 10f, 5f, 0f, 15f, 10f), TOLERANCE)
    }

    @Test
    fun `a box inside another scores the ratio of their areas`() {
        // Intersection 25, union 100 + 25 - 25 = 100.
        assertEquals(0.25f, overlap(0f, 0f, 10f, 10f, 0f, 0f, 5f, 5f), TOLERANCE)
    }

    @Test
    fun `degenerate boxes are safe`() {
        assertEquals(0f, overlap(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), TOLERANCE)
        assertEquals(0f, overlap(5f, 5f, 5f, 10f, 0f, 0f, 10f, 10f), TOLERANCE)
    }

    @Test
    fun `a slightly drifted reading still overlaps enough to be the same block`() {
        // A block that moved by a tenth of its width between passes should still
        // be recognised as the same one.
        val score = overlap(0f, 0f, 100f, 20f, 10f, 1f, 110f, 21f)
        assertTrue("expected a strong overlap but was $score", score > 0.7f)
    }

    private fun overlap(
        aLeft: Float,
        aTop: Float,
        aRight: Float,
        aBottom: Float,
        bLeft: Float,
        bTop: Float,
        bRight: Float,
        bBottom: Float
    ): Float = BlockMatcher.overlap(aLeft, aTop, aRight, aBottom, bLeft, bTop, bRight, bBottom)

    private companion object {
        const val TOLERANCE = 1e-6f
    }
}
