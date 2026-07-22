package io.github.diet103.lector.data

import android.net.Uri
import io.github.diet103.lector.history.ReadSource
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.tts.CacheKeys
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process handoff for speak requests. The player only ever sees a `lector://tts/<key>` URI;
 * the text itself never rides a binder transaction or a MediaItem extra.
 */
class SpeakRequestRegistry {

    private val requests = ConcurrentHashMap<String, SpeakRequest>()
    private val contexts = ConcurrentHashMap<String, ReadContext>()

    fun register(request: SpeakRequest, context: ReadContext? = null): Uri {
        val key = CacheKeys.forRequest(request)
        requests[key] = request
        if (context != null) contexts[key] = context
        return uriForKey(key)
    }

    fun byKey(key: String): SpeakRequest? = requests[key]

    /** Where this read came from, for the history row. Absent for a replay of an old entry. */
    fun contextFor(key: String): ReadContext? = contexts[key]

    /** The player-facing URI for a key; its last path segment is what the resolver reads back. */
    fun uriForKey(key: String): Uri = Uri.parse("$SCHEME://tts/$key")

    companion object {
        const val SCHEME = "lector"
    }
}

/**
 * How a read reached Lector, carried alongside the request so
 * [PlaybackService][io.github.diet103.lector.playback.PlaybackService] can write an honest history
 * row once the audio actually starts.
 */
data class ReadContext(
    val source: ReadSource,
    /** A link's headline; null for everything else. */
    val title: String? = null,
    /** The page a link read came from. Never set for a screenshot — the image URI is not kept. */
    val sourceUrl: String? = null
)
