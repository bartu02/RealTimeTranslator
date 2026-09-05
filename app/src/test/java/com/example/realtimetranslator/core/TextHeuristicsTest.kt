package com.example.realtimetranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHeuristicsTest {

    @Test
    fun `accepts ordinary words`() {
        assertTrue(TextHeuristics.isPotentiallyMeaningful("Ausgang"))
        assertTrue(TextHeuristics.isPotentiallyMeaningful("im"))
        assertTrue(TextHeuristics.isPotentiallyMeaningful("Gleis 7"))
    }

    @Test
    fun `rejects strings that are too short`() {
        assertFalse(TextHeuristics.isPotentiallyMeaningful(""))
        assertFalse(TextHeuristics.isPotentiallyMeaningful("a"))
    }

    @Test
    fun `rejects digit heavy strings`() {
        assertFalse(TextHeuristics.isPotentiallyMeaningful("12345"))
        assertFalse(TextHeuristics.isPotentiallyMeaningful("a1234"))
    }

    @Test
    fun `rejects long strings without vowels`() {
        assertFalse(TextHeuristics.isPotentiallyMeaningful("bcdfghjk"))
    }

    @Test
    fun `rejects repeated characters and symbol noise`() {
        assertFalse(TextHeuristics.isPotentiallyMeaningful("aaaa"))
        assertFalse(TextHeuristics.isPotentiallyMeaningful("||||"))
        assertFalse(TextHeuristics.isPotentiallyMeaningful("we~ird"))
    }

    @Test
    fun `rejects runs of characters the recognizer confuses`() {
        assertFalse(TextHeuristics.isPotentiallyMeaningful("aIl1b"))
    }

    @Test
    fun `accepts words with umlauts`() {
        assertTrue(TextHeuristics.isPotentiallyMeaningful("Fahrplanänderung"))
        assertTrue(TextHeuristics.isPotentiallyMeaningful("Straße"))
    }

    @Test
    fun `normalize collapses case and whitespace`() {
        assertEquals("hello world", TextHeuristics.normalize("  Hello   \n WORLD  "))
    }

    @Test
    fun `levenshtein counts single edits`() {
        assertEquals(0, TextHeuristics.levenshtein("kitten", "kitten"))
        assertEquals(1, TextHeuristics.levenshtein("kitten", "sitten"))
        assertEquals(3, TextHeuristics.levenshtein("kitten", "sitting"))
    }

    @Test
    fun `levenshtein handles empty strings`() {
        assertEquals(4, TextHeuristics.levenshtein("", "abcd"))
        assertEquals(4, TextHeuristics.levenshtein("abcd", ""))
        assertEquals(0, TextHeuristics.levenshtein("", ""))
    }

    @Test
    fun `levenshtein is symmetric`() {
        assertEquals(
            TextHeuristics.levenshtein("Ausfahrt", "Ausfhart"),
            TextHeuristics.levenshtein("Ausfhart", "Ausfahrt")
        )
    }

    @Test
    fun `similarity is one for identical strings and zero for empty comparison`() {
        assertEquals(1.0, TextHeuristics.similarity("gleis", "gleis"), 1e-9)
        assertEquals(1.0, TextHeuristics.similarity("", ""), 1e-9)
        assertEquals(0.0, TextHeuristics.similarity("abc", ""), 1e-9)
    }

    @Test
    fun `similarity rates a small typo as close`() {
        assertTrue(TextHeuristics.similarity("ausgang", "ausgangs") > 0.8)
        assertTrue(TextHeuristics.similarity("ausgang", "bahnhof") < 0.5)
    }
}
