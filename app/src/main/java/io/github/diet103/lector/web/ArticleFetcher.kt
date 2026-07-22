package io.github.diet103.lector.web

import android.util.Log
import io.github.diet103.lector.model.TtsError
import io.github.diet103.lector.tts.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a shared link and pulls the readable text out of it.
 *
 * Bounded on purpose: a shared link is arbitrary internet, so the fetch is size-capped, given its
 * own timeouts, and refuses anything that isn't HTML — otherwise sharing a link to a 200 MB video
 * would sit there consuming the user's data to produce nothing.
 */
class ArticleFetcher(client: OkHttpClient) {

    private val client = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(url: String): ApiResult<ArticleExtractor.Article> {
        if (SharedLink.isWalledOff(url)) return ApiResult.Failed(TtsError.LinkWalledOff)

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ApiResult.Failed(TtsError.LinkNotReadable)
                    }

                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("html", ignoreCase = true)) {
                        return@withContext ApiResult.Failed(TtsError.LinkNotReadable)
                    }

                    val body = response.body ?: return@withContext ApiResult.Failed(TtsError.LinkNotReadable)
                    val html = body.source().let { source ->
                        source.request(MAX_BYTES)
                        source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
                    }

                    val article = ArticleExtractor.extract(html, url)
                    if (article == null) {
                        // Shapes only — how much HTML came back, never what it said.
                        Log.i(TAG, "no readable article in ${html.length} chars of html")
                        return@withContext ApiResult.Failed(TtsError.LinkNotReadable)
                    }
                    ApiResult.Ok(article)
                }
            } catch (failure: Exception) {
                // Unconditional, and deliberately so. R8 silently removing a keep-rule-dependent
                // constructor took out the whole OCR path while a catch like this reported it as an
                // ordinary "couldn't read that" — an exception type here is the difference between
                // a diagnosable bug report and a shrug.
                Log.w(TAG, "fetch failed", failure)
                ApiResult.Failed(
                    io.github.diet103.lector.tts.ElevenLabsErrorMapper.mapFailure(failure)
                )
            }
        }
    }

    private companion object {
        const val TAG = "LectorLink"
        const val MAX_BYTES = 3L * 1024 * 1024

        /** Sites serve very different HTML to unknown agents; a normal mobile browser gets prose. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
