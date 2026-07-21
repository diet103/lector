package io.github.diet103.lector.playback

import android.content.Context
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.diet103.lector.data.SpeakRequestRegistry
import okhttp3.OkHttpClient

/**
 * Assembles the load-bearing chain (player → network):
 *
 *   ProgressiveMediaSource (single-shot error policy, fast-start buffer)
 *     └─ ResolvingDataSource(TtsDataSpecResolver)   lector:// → POST spec
 *         └─ CacheDataSource(SimpleCache)           write-through, key = content hash
 *             └─ GuardedUpstreamDataSource          refuses offset re-requests
 *                 └─ OkHttpDataSource               executes the POST, streams MP3
 */
object TtsPlayerFactory {

    private const val FAST_START_BUFFER_MS = 500

    fun create(
        context: Context,
        registry: SpeakRequestRegistry,
        cache: Cache,
        okHttpClient: OkHttpClient,
        apiKeyProvider: () -> String
    ): ExoPlayer {
        val network = OkHttpDataSource.Factory(okHttpClient)
        val guarded = GuardedUpstreamDataSource.Factory(network)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(guarded)
        val resolving = ResolvingDataSource.Factory(
            cached,
            TtsDataSpecResolver(registry, apiKeyProvider)
        )

        val mediaSourceFactory = ProgressiveMediaSource.Factory(resolving)
            .setLoadErrorHandlingPolicy(SingleShotLoadErrorHandlingPolicy())

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                FAST_START_BUFFER_MS,
                FAST_START_BUFFER_MS * 2
            )
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
    }
}
