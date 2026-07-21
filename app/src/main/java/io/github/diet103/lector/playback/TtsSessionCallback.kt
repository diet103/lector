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
    private val registry: SpeakRequestRegistry
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

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(availablePlayerCommands)
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
