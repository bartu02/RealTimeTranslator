package com.example.realtimetranslator.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The online translation path, which the mode picker still advertises as coming
 * soon. Nothing calls it yet.
 *
 * The key is a constructor parameter rather than a constant in the source, so
 * enabling this means supplying a credential from somewhere it belongs instead
 * of committing one.
 */
class OnlineTranslator(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    /** Returns [text] unchanged when the call fails, so callers can always draw something. */
    suspend fun translate(
        text: String,
        sourceLanguage: String = "DE",
        targetLanguage: String = "EN"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext text

        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .addQueryParameter("source_lang", sourceLanguage)
            .addQueryParameter("target_lang", targetLanguage)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext text
                if (!response.isSuccessful) return@withContext text

                JSONObject(body)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
            }
        } catch (throwable: Throwable) {
            text
        }
    }

    private companion object {
        const val ENDPOINT = "https://api-free.deepl.com/v2/translate"
    }
}
