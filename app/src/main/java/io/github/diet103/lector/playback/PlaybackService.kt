package io.github.diet103.lector.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.diet103.lector.LectorApplication
import io.github.diet103.lector.MainActivity
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.ReadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground media service (PLAN §6 P2). Owns the single [ExoPlayer] and one [MediaSession];
 * Media3 draws the media notification and runs the `startForeground` dance itself — we never
 * call `startForegroundService`, which sidesteps the FGS-promotion race (§4). The player, cache,
 * registry, and HTTP client all come from the shared [AppContainer][io.github.diet103.lector.app.AppContainer],
 * so a `lector://tts/<key>` handed in by a controller resolves against the same registry the UI
 * wrote to.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stopSelf() }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val container = (application as LectorApplication).container

        player = TtsPlayerFactory.create(
            context = this,
            registry = container.registry,
            cache = container.cache,
            okHttpClient = container.okHttpClient,
            apiKeyProvider = container.apiKeyProvider
        )
        player.addListener(IdleStopListener())
        // The scrim that started this read is usually gone by the time a stream fails, and the
        // notification can't explain itself — so the ledger is the only place the reason survives.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                container.lastError.record(PlaybackErrorMapper.map(error))
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(TtsSessionCallback(container.registry))
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(HistoryRecorder(container))

        // Speed is applied by the player, never by the API — so dragging the slider re-pitches
        // what's already playing and cannot re-synthesise or re-bill (PLAN §6 P6).
        serviceScope.launch {
            container.settings.speed.collect { player.setPlaybackSpeed(it) }
        }
    }

    /**
     * Writes the reading history (v0.2).
     *
     * Recording happens on the first `isPlaying`, not when the read is requested: a read that dies
     * on a bad key or a dead network never becomes something the user "read". The duration and
     * byte count only exist once playback ends, so they arrive in a second write — an abandoned
     * read still gets a row, just without them, and honestly reads as not-free-to-replay.
     */
    private inner class HistoryRecorder(
        private val container: io.github.diet103.lector.app.AppContainer
    ) : Player.Listener {

        /** Guards against re-recording the same item on every pause/resume within one read. */
        private var recordedKey: String? = null

        /** A new item is a new read, even if it's the same text being replayed. */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recordedKey = null
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            val key = player.currentMediaItem?.mediaId ?: return
            if (key == recordedKey || !container.settings.historyEnabled.value) return

            val request = container.registry.byKey(key) ?: return
            val context = container.registry.contextFor(key)
            recordedKey = key
            val now = System.currentTimeMillis()

            serviceScope.launch(Dispatchers.IO) {
                // A replay carries no ReadContext, and inventing one would relabel a screenshot
                // read as shared text. If the row already exists, only its position in the list
                // should move.
                if (context == null && container.history.touch(key, now)) return@launch

                container.history.record(
                    HistoryEntry(
                        key = key,
                        text = request.text,
                        title = context?.title,
                        source = context?.source ?: ReadSource.SHARED_TEXT,
                        sourceUrl = context?.sourceUrl,
                        voiceId = request.voiceId,
                        modelId = request.modelId,
                        outputFormat = request.outputFormat,
                        createdAt = now,
                        lastReadAt = now,
                        durationMs = null,
                        audioBytes = null
                    )
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val key = recordedKey ?: return
            val duration = player.duration
            if (duration == C.TIME_UNSET) return

            serviceScope.launch(Dispatchers.IO) {
                // Contiguous bytes from the start — what CacheDataSource actually holds, and the
                // same question `isFullyCached` asks later.
                val cached = container.cache.getCachedBytes(key, 0, Long.MAX_VALUE)
                if (cached > 0) container.history.markCompleted(key, duration, cached)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Swiped away from Recents with nothing playing: nothing to keep alive, so shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = player.playWhenReady && player.mediaItemCount > 0
        if (!active) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        idleHandler.removeCallbacks(idleStop)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Stop the service after [IDLE_STOP_MS] of no active playback so a paused read doesn't hold a
     * foreground service (and its wake lock) open indefinitely. Any resumed playback cancels it.
     */
    private inner class IdleStopListener : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reschedule()
        override fun onPlaybackStateChanged(playbackState: Int) = reschedule()

        private fun reschedule() {
            idleHandler.removeCallbacks(idleStop)
            val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE
            if (!playing) {
                idleHandler.postDelayed(idleStop, IDLE_STOP_MS)
            }
        }
    }

    private companion object {
        const val IDLE_STOP_MS = 10L * 60 * 1000
    }
}
