package io.github.diet103.lector.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.data.SpeakRequestRegistry

/**
 * The session's gatekeeper (PLAN §5):
 *  - **Seek stripping** — every `COMMAND_SEEK_*` is removed from what any controller
 *    (notification, Quick Settings, Assistant) may do. No scrubber can exist, so no scrub can
 *    re-open the stream at an offset and re-bill (billing defense #2, §4). This is unconditional:
 *    an earlier version handed the seek commands out when the current read happened to be cached,
 *    which made the guarantee depend on timing and broke tap-a-word rather than enabling it.
 *  - **Media-ID resolution** — a controller may only hand us a media *id*, never an arbitrary
 *    URI across the binder. We look the id up in the in-process registry and attach the real
 *    URI the resolver chain understands. Unknown ids pass through unchanged and simply fail to
 *    load.
 *  - **Reading from a point** — [ReadFromCommand] instead, answered by the service. See that file
 *    for why it isn't a player command.
 *  - **Resumption declined** — no "resume last playback" from a cold start: it would silently
 *    re-POST paid audio the user never asked to hear again.
 */
class TtsSessionCallback(
    private val registry: SpeakRequestRegistry,
    /**
     * Whether every byte of this key is already on disk. Consulted when a controller hands over a
     * media id, so a read that costs nothing to replay is pointed at the replay-only URI that
     * [GuardedUpstreamDataSource] refuses to fetch.
     */
    private val isFullyCached: (String) -> Boolean = { false },
    /** Runs the reader's "start from here" request on the player. Returns the reply bundle. */
    private val readFrom: (Long) -> Bundle = { Bundle.EMPTY }
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
     * Which session commands a controller gets. Only Lector's own UI may ask to read from a point;
     * the media notification is excluded along with everything outside this package, so a car head
     * unit or Assistant can still play and pause but cannot move the playhead. Pure and
     * session-free so the rule can be pinned by a test.
     */
    fun sessionCommandsFor(isOwnPackage: Boolean, isMediaNotification: Boolean) =
        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .apply { if (isOwnPackage && !isMediaNotification) add(ReadFromCommand.COMMAND) }
            .build()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(availablePlayerCommands)
            .setAvailableSessionCommands(
                sessionCommandsFor(
                    isOwnPackage = controller.packageName == BuildConfig.APPLICATION_ID,
                    isMediaNotification = session.isMediaNotificationController(controller)
                )
            )
            .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != ReadFromCommand.ACTION) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        val positionMs = args.getLong(ReadFromCommand.ARG_POSITION_MS, 0L)
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, readFrom(positionMs)))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(resolve(mediaItems))

    /**
     * A controller may hand us only a media *id*; attach the real URI from the registry. A key
     * whose audio is entirely on disk gets the replay-only `lector://cached/` form, so playing it
     * cannot reach the network even if the cache is evicted between this check and the read.
     * Unknown ids pass through untouched and simply fail to load. Pure and session-free so it can
     * be unit-tested directly.
     */
    fun resolve(mediaItems: List<MediaItem>): MutableList<MediaItem> =
        mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
            when {
                registry.byKey(item.mediaId) == null -> item
                isFullyCached(item.mediaId) ->
                    item.buildUpon().setUri(registry.cachedUriForKey(item.mediaId)).build()
                else -> item.buildUpon().setUri(registry.uriForKey(item.mediaId)).build()
            }
        }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Lector: playback resumption is declined"))
}
