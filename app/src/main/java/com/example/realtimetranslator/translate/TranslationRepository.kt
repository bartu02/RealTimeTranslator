package com.example.realtimetranslator.translate

import com.example.realtimetranslator.core.awaitCompletion
import com.example.realtimetranslator.core.awaitResult
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * On-device translation behind a bounded LRU cache.
 *
 * The cache does most of the work: a live camera re-reads the same words many
 * times a second, so after the first pass almost every lookup is a hit. It is
 * bounded because the previous plain map grew for as long as the app ran.
 */
class TranslationRepository(
    sourceLanguage: String = TranslateLanguage.GERMAN,
    targetLanguage: String = TranslateLanguage.ENGLISH
) {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
    )

    private val cache = object : LinkedHashMap<String, String>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_CAPACITY
    }

    @Volatile
    var isReady: Boolean = false
        private set

    /** Downloads the language pair if needed. Safe to call more than once. */
    suspend fun prepare(): Boolean = try {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitCompletion()
        isReady = true
        true
    } catch (throwable: Throwable) {
        isReady = false
        false
    }

    suspend fun translate(text: String): String {
        val key = text.trim()
        if (key.isEmpty()) return text

        synchronized(cache) { cache[key] }?.let { return it }

        val translated = try {
            translator.translate(key).awaitResult()
        } catch (throwable: Throwable) {
            key
        }

        synchronized(cache) { cache[key] = translated }
        return translated
    }

    /** Translates a batch concurrently; cached entries resolve immediately. */
    suspend fun translateAll(texts: List<String>): Map<String, String> = coroutineScope {
        texts.distinct()
            .map { text -> async { text to translate(text) } }
            .awaitAll()
            .toMap()
    }

    fun close() = translator.close()

    private companion object {
        const val CACHE_CAPACITY = 256
    }
}
