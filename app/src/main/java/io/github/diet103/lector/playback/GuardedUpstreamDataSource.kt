package io.github.diet103.lector.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.diet103.lector.data.SpeakRequestRegistry
import java.io.IOException

/**
 * Last line of the billing defense: sits directly above the network source, below the cache.
 * It refuses two things.
 *
 * **Any upstream open at a nonzero byte offset.** That means the player wants to resume a stream
 * mid-generation — which ElevenLabs cannot do (it would re-bill the full text and return a
 * *different* generation, splicing garbage audio).
 *
 * **Any upstream open at all for a `lector://cached/` read.** Reaching this code means the cache
 * missed, and for a read the user was told is free to replay a cache miss must never turn into a
 * purchase. Nothing else in the chain can make that promise: opening at byte zero is exactly what
 * a first read does, so position alone cannot tell the two apart.
 */
class GuardedUpstreamDataSource(private val delegate: DataSource) : DataSource by delegate {

    override fun open(dataSpec: DataSpec): Long {
        if (SpeakRequestRegistry.isCacheOnly(dataSpec.uri)) {
            throw IOException(
                "Lector: ${dataSpec.uri.lastPathSegment} was expected to be on disk but isn't — " +
                    "refusing to re-synthesise it, because replaying a stored read is meant to be free."
            )
        }
        if (dataSpec.position > 0) {
            throw IOException(
                "Lector: refusing to re-request TTS audio at byte offset ${dataSpec.position} — " +
                    "this would re-bill and splice a different generation. Replay from the start instead."
            )
        }
        return delegate.open(dataSpec)
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            GuardedUpstreamDataSource(upstream.createDataSource())
    }
}
