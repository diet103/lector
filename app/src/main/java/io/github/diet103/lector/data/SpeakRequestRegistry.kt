package io.github.diet103.lector.data

import android.net.Uri
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.tts.CacheKeys
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process handoff for speak requests. The player only ever sees a `lector://tts/<key>` URI;
 * the text itself never rides a binder transaction or a MediaItem extra.
 */
class SpeakRequestRegistry {

    private val requests = ConcurrentHashMap<String, SpeakRequest>()

    fun register(request: SpeakRequest): Uri {
        val key = CacheKeys.forRequest(request)
        requests[key] = request
        return uriForKey(key)
    }

    fun byKey(key: String): SpeakRequest? = requests[key]

    /** The player-facing URI for a key; its last path segment is what the resolver reads back. */
    fun uriForKey(key: String): Uri = Uri.parse("$SCHEME://tts/$key")

    companion object {
        const val SCHEME = "lector"
    }
}
