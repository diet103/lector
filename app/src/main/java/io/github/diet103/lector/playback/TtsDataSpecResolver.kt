package io.github.diet103.lector.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.diet103.lector.data.SpeakRequestRegistry
import org.json.JSONObject
import java.io.IOException

/**
 * Rewrites the player's `lector://tts/<key>` reads into the ElevenLabs streaming POST.
 *
 * Two deliberate details:
 *  - `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` is stripped: ExoPlayer's progressive loader sets it on
 *    every request, and the chunked TTS response has unknown length — left in place, the
 *    write-through cache would silently never be written (verified against Media3 1.10 source).
 *  - The cache key is the content hash, set explicitly so CacheDataSource keys by content,
 *    not by URL.
 *
 * A `lector://cached/<key>` read is left pointing at itself rather than rewritten. It only ever
 * needs the cache key; if the cache misses, the un-rewritten URI is what
 * [GuardedUpstreamDataSource] recognises and refuses, so a stored read can never quietly become a
 * purchase. The registry is deliberately *not* consulted for it either — a replay needs no request
 * body, and requiring one would break replaying after the in-memory registry has been lost.
 */
class TtsDataSpecResolver(
    private val registry: SpeakRequestRegistry,
    private val apiKeyProvider: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val key = dataSpec.uri.lastPathSegment
            ?: throw IOException("Lector: malformed TTS uri ${dataSpec.uri}")

        if (SpeakRequestRegistry.isCacheOnly(dataSpec.uri)) {
            return dataSpec.buildUpon()
                .setKey(key)
                .setFlags(dataSpec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv())
                .build()
        }

        val request = registry.byKey(key)
            ?: throw IOException("Lector: no registered speak request for $key")

        val body = JSONObject()
            .put("text", request.text)
            .put("model_id", request.modelId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val url = "$baseUrl/v1/text-to-speech/${request.voiceId}/stream" +
            "?output_format=${request.outputFormat}"

        return dataSpec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(body)
            .setHttpRequestHeaders(
                mapOf(
                    "xi-api-key" to apiKeyProvider(),
                    "Content-Type" to "application/json",
                    "Accept" to "audio/mpeg"
                )
            )
            .setKey(key)
            .setFlags(dataSpec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv())
            .build()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.elevenlabs.io"
    }
}
