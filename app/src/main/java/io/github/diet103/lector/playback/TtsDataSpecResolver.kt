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
 */
class TtsDataSpecResolver(
    private val registry: SpeakRequestRegistry,
    private val apiKeyProvider: () -> String
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val key = dataSpec.uri.lastPathSegment
            ?: throw IOException("Lector: malformed TTS uri ${dataSpec.uri}")
        val request = registry.byKey(key)
            ?: throw IOException("Lector: no registered speak request for $key")

        val body = JSONObject()
            .put("text", request.text)
            .put("model_id", request.modelId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val url = "https://api.elevenlabs.io/v1/text-to-speech/${request.voiceId}/stream" +
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
}
