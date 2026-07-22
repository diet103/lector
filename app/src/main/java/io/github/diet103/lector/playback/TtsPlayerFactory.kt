package io.github.diet103.lector.playback

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

    /**
     * Effectively "no time limit" — longer than any read the caps allow (40k characters is roughly
     * 47 minutes). Media3's own `DEFAULT_AUDIO_BUFFER_SIZE` of 12.5 MB stays the real brake, which
     * is why [DefaultLoadControl.Builder.setTargetBufferBytes] is deliberately left alone.
     */
    private const val EAGER_BUFFER_MS = 60 * 60 * 1000

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

        // Drain the response as fast as the network allows instead of trickling 50 s ahead of the
        // playhead (Media3's default). The characters were billed the instant the POST went out, so
        // pulling the rest early costs nothing — but it decides whether the reader works at all:
        // seeking is only safe on cached audio, and Media3 builds a *non-seekable* seek map for an
        // MP3 whose length it doesn't know yet (a chunked TTS response). Under the old ceiling a
        // read wasn't fully on disk until it was nearly over, so tapping a word did nothing useful
        // until then — and `duration` stayed TIME_UNSET, so nothing highlighted either.
        //
        // Only the streaming values move. `lector://` is never one of Media3's local-playback
        // schemes (file/content/data/android.resource/rawresource/asset), so that branch can't be
        // taken — and its `prioritizeTimeOverSizeThresholds` default of true would ignore the byte
        // ceiling and let an hour of audio pile up in memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                EAGER_BUFFER_MS,
                EAGER_BUFFER_MS,
                FAST_START_BUFFER_MS,
                FAST_START_BUFFER_MS * 2
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setClock(clock)
            // handleAudioFocus=true: pause on calls, duck for nav prompts. Becoming-noisy pauses
            // on headphone unplug. Wake mode keeps CPU + network alive with the screen off — the
            // whole point is finishing a read after you pocket the phone (WAKE_LOCK approved §2).
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
