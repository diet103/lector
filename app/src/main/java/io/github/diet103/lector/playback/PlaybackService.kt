package io.github.diet103.lector.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.LectorApplication
import io.github.diet103.lector.MainActivity
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.ReadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var sessionCallback: TtsSessionCallback
    private lateinit var container: AppContainer
    private lateinit var historyRecorder: HistoryRecorder

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stopSelf() }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = (application as LectorApplication).container

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

        sessionCallback = TtsSessionCallback(
            registry = container.registry,
            isFullyCached = container::isFullyCached,
            readFrom = ::readFrom
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()

        historyRecorder = HistoryRecorder(container)
        player.addListener(historyRecorder)

        // Speed is applied by the player, never by the API — so dragging the slider re-pitches
        // what's already playing and cannot re-synthesise or re-bill (PLAN §6 P6).
        serviceScope.launch {
            container.settings.speed.collect { player.setPlaybackSpeed(it) }
        }
    }

    /**
     * The reader's "start reading from here", and the **only** place that decides whether a jump is
     * allowed (v0.2). See [SeekDecision] for the rule and why both of its inputs are needed.
     *
     * This used to be split between a listener here that handed out seek commands at item
     * transitions and a cache check inside the reader screen. The two drifted — a read that was
     * still downloading when it started playing was denied for the rest of its life, while the
     * reader decided it *was* allowed the moment the download finished — so the reader called
     * `seekTo`, Media3 silently dropped an ungranted command, and tapping a word did nothing.
     * Deciding here, at the moment of the tap, with the cache and the player both in hand, is what
     * makes that class of bug impossible rather than merely fixed.
     *
     * @return the reply bundle for [ReadFromCommand].
     */
    private fun readFrom(positionMs: Long): Bundle {
        val key = player.currentMediaItem?.mediaId
        val cached = key != null && container.isFullyCached(key)
        // Read before acting: a re-prepare changes it, and a log that reported the *result* rather
        // than the reason would be worse than no log at all.
        val canSeek = player.isCurrentMediaItemSeekable
        val decision = SeekDecision.decide(cached, canSeek)

        when {
            key == null || decision == SeekDecision.REFUSE -> Unit

            decision == SeekDecision.SEEK -> player.seekTo(positionMs)

            else -> {
                // Same read, prepared again from the finished file so it finally has a seek map.
                // Tell the recorder first, or it would log this as a fresh read and the re-record
                // would wipe the duration the reader has just learned.
                historyRecorder.expectSameRead()
                player.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(key)
                        .setUri(container.registry.cachedUriForKey(key))
                        .build(),
                    positionMs
                )
                player.prepare()
                player.play()
            }
        }

        if (BuildConfig.DEBUG) {
            // Shapes only, never the text. Its absence is why this bug survived three rounds of
            // "it still doesn't work" with nothing in logcat to point at.
            Log.d(
                TAG,
                "readFrom key=${key?.take(8)} cached=$cached playerCanSeek=$canSeek " +
                    "decision=$decision target=${positionMs}ms landed=${player.currentPosition}ms"
            )
        }

        return Bundle().apply {
            putString(ReadFromCommand.RESULT_DECISION, decision.name)
            putLong(ReadFromCommand.RESULT_POSITION_MS, player.currentPosition)
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
        private val container: AppContainer
    ) : Player.Listener {

        /** Guards against re-recording the same item on every pause/resume within one read. */
        private var recordedKey: String? = null

        /**
         * Set when the next item change is the reader jumping to a word, which re-prepares the
         * same read rather than starting a different one.
         */
        private var sameReadExpected = false

        /** @see readFrom */
        fun expectSameRead() {
            sameReadExpected = true
        }

        /**
         * A new item is a new read, even if it's the same text being replayed — except when we
         * re-prepared it ourselves to make it seekable. Treating that as new would write the row
         * again, and [HistoryStore.record][io.github.diet103.lector.history.HistoryStore.record]
         * replaces the row, losing the duration the reader depends on for its highlight.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val continuing = sameReadExpected && mediaItem?.mediaId == recordedKey
            sameReadExpected = false
            if (!continuing) recordedKey = null
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
        const val TAG = "Lector"
    }
}
