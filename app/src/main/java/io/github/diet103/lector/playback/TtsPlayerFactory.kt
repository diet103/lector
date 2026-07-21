package io.github.diet103.lector.playback

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.util.Clock
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
 *
 * [baseUrl] and [clock] exist for the Robolectric billing suite: tests point the chain at a
 * MockWebServer and drive playback on an auto-advancing [FakeClock][androidx.media3.test.utils];
 * production call sites take the defaults. (`setClock` is marked visible-for-testing upstream,
 * hence the suppression.)
 */
object TtsPlayerFactory {

    private const val FAST_START_BUFFER_MS = 500

    @SuppressLint("VisibleForTests")
    fun create(
        context: Context,
        registry: SpeakRequestRegistry,
        cache: Cache,
        okHttpClient: OkHttpClient,
        baseUrl: String = TtsDataSpecResolver.DEFAULT_BASE_URL,
        clock: Clock = Clock.DEFAULT,
        apiKeyProvider: () -> String
    ): ExoPlayer {
        val network = OkHttpDataSource.Factory(okHttpClient)
        val guarded = GuardedUpstreamDataSource.Factory(network)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(guarded)
        val resolving = ResolvingDataSource.Factory(
            cached,
            TtsDataSpecResolver(registry, apiKeyProvider, baseUrl)
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

        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setClock(clock)
        if (clock !== Clock.DEFAULT) {
            // The stuck-playback watchdogs are calibrated to wall-clock time. An injected clock
            // (the test suite's auto-advancing FakeClock) leaps through fake time while real
            // network I/O stalls, so the thresholds fire spuriously — park them out of reach.
            builder.setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE)
                .setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE)
        }
        return builder.build()
    }
}
