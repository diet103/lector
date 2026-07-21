package io.github.diet103.lector.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Last line of the billing defense: sits directly above the network source, below the cache.
 * Any upstream open at a nonzero byte offset means the player wants to resume a stream
 * mid-generation — which ElevenLabs cannot do (it would re-bill the full text and return a
 * *different* generation, splicing garbage audio). Fail loudly instead.
 */
class GuardedUpstreamDataSource(private val delegate: DataSource) : DataSource by delegate {

    override fun open(dataSpec: DataSpec): Long {
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
