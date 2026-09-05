package com.example.realtimetranslator.core

/**
 * Pure text helpers used to reject OCR noise and to detect whether the scene
 * still shows the same words. Kept free of Android types so they stay testable.
 */
object TextHeuristics {

    private val VOWEL = Regex("[aeiouyäöüåæøßà-ãè-ïò-õù-ûı]", RegexOption.IGNORE_CASE)
    private val NOISE_SYMBOLS = Regex("[~`@#%^*_+=<>|]")
    private val CONFUSABLE_RUN = Regex("[Il1|]{3,}")
    private val WHITESPACE = Regex("""\s+""")

    /** Cheap filter that drops strings which are almost certainly recognizer noise. */
    fun isPotentiallyMeaningful(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false

        val letters = t.count(Char::isLetter)
        val digits = t.count(Char::isDigit)

        if (letters.toDouble() / t.length < 0.5) return false
        if (digits > letters) return false
        if (t.length > 5 && !VOWEL.containsMatchIn(t)) return false
        if (t.all { it == t[0] }) return false
        if (NOISE_SYMBOLS.containsMatchIn(t)) return false
        if (CONFUSABLE_RUN.containsMatchIn(t)) return false

        return true
    }

    fun normalize(text: String): String = text.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * Levenshtein distance over two rolling rows, so memory is O(min(a, b))
     * instead of the O(a * b) matrix a naive version allocates.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Keep the shorter string on the inner axis to minimise the row size.
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a

        var previous = IntArray(short.length + 1) { it }
        var current = IntArray(short.length + 1)

        for (i in 1..long.length) {
            current[0] = i
            val longChar = long[i - 1]
            for (j in 1..short.length) {
                val substitution = previous[j - 1] + if (short[j - 1] == longChar) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[short.length]
    }

    /** 1.0 when identical, 0.0 when completely different. */
    fun similarity(a: String, b: String): Double {
        val maxLength = maxOf(a.length, b.length).toDouble()
        if (maxLength == 0.0) return 1.0
        return 1.0 - levenshtein(a, b) / maxLength
    }
}
