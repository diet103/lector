package io.github.diet103.lector.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.diet103.lector.data.SpeakRequestRegistry

/**
 * The session's gatekeeper (PLAN §5):
 *  - **Seek stripping** — every `COMMAND_SEEK_*` is removed from what any controller
 *    (notification, Quick Settings, Assistant) may do. No scrubber can exist, so no scrub can
 *    re-open the stream at an offset and re-bill (billing defense #2, §4). v0.1 shows elapsed
 *    time only; real seeking waits on tee-to-file (backlog).
 *  - **Media-ID resolution** — a controller may only hand us a media *id*, never an arbitrary
 *    URI across the binder. We look the id up in the in-process registry and attach the real
 *    `lector://tts/<key>` URI the resolver chain understands. Unknown ids pass through unchanged
 *    and simply fail to load.
 *  - **Resumption declined** — no "resume last playback" from a cold start: it would silently
 *    re-POST paid audio the user never asked to hear again.
 */
class TtsSessionCallback(
    private val registry: SpeakRequestRegistry,
    /**
     * Whether the read currently loaded is entirely on disk, and so free to seek within. Consulted
     * at **connect** time as well as on item changes: the reader screen builds its controller after
     * playback is already under way, so a connect that always returned the stripped set would leave
     * it permanently unable to seek no matter what the service granted later.
     */
    private val seekAllowedNow: () -> Boolean = { false }
) : MediaSession.Callback {

    /** Default player commands with every seek removed. Exposed so a test can pin the guarantee. */
    val availablePlayerCommands: Player.Commands = Player.Commands.Builder()
        .addAll(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        .removeAll(
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD
        )
        .build()

    /**
     * The above, plus seeking *within the current item only* — granted by
     * [PlaybackService][io.github.diet103.lector.playback.PlaybackService] to in-app controllers
     * when every byte of the current read is already on disk, so the reader's tap-a-word can
     * actually move the playhead.
     *
     * Still no track-to-track seeking, and still nothing granted to the media notification: the
     * shade's controls stay identical whether or not a read happens to be cached, rather than
     * growing a scrubber that sometimes costs money and sometimes doesn't.
     */
    val cachedSeekPlayerCommands: Player.Commands = Player.Commands.Builder()
        .addAll(availablePlayerCommands)
        .addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD
        )
        .build()

    /**
     * Which command set a controller gets. Pure and session-free so the rule can be pinned by a
     * test — the bug this replaced was a controller that connected *after* playback had started
     * and so never received the grant the service had already handed out.
     */
    fun commandsFor(isMediaNotification: Boolean, seekAllowed: Boolean): Player.Commands =
        if (!isMediaNotification && seekAllowed) cachedSeekPlayerCommands else availablePlayerCommands

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(
                commandsFor(
                    isMediaNotification = session.isMediaNotificationController(controller),
                    seekAllowed = seekAllowedNow()
                )
            )
            .build()

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(resolve(mediaItems))

    /**
     * A controller may hand us only a media *id*; attach the real `lector://tts/<key>` URI from
     * the registry. Unknown ids pass through untouched and simply fail to load. Pure and
     * session-free so it can be unit-tested directly.
     */
    fun resolve(mediaItems: List<MediaItem>): MutableList<MediaItem> =
        mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
            when (registry.byKey(item.mediaId)) {
                null -> item
                else -> item.buildUpon().setUri(registry.uriForKey(item.mediaId)).build()
            }
        }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Lector: playback resumption is declined"))
}
