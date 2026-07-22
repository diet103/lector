package io.github.diet103.lector.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * How the reader asks to start reading from a particular point.
 *
 * Deliberately *not* `Player.seekTo`. A controller's player commands are granted asynchronously and
 * per-controller, and Media3 drops an ungranted one in silence — so a screen that decided for itself
 * that seeking was allowed could call `seekTo` and have nothing happen, with no error anywhere. That
 * is half of why tap-a-word was unusable.
 *
 * A custom session command inverts it: the reader *asks*, [PlaybackService] decides with the cache
 * and the player both in front of it, and the answer comes back in the reply. Every `COMMAND_SEEK_*`
 * stays stripped from every controller, so the media notification still cannot grow a scrubber.
 */
object ReadFromCommand {

    const val ACTION = "io.github.diet103.lector.READ_FROM"

    /** Request: where in the audio to start, in milliseconds. */
    const val ARG_POSITION_MS = "positionMs"

    /** Reply: the name of the [SeekDecision] that was taken. */
    const val RESULT_DECISION = "decision"

    /**
     * Reply: where playback actually ended up. The reader pins its caret here until the player's
     * reported position catches up — otherwise the position poller overwrites the caret with the
     * pre-seek position and the highlight visibly snaps back.
     */
    const val RESULT_POSITION_MS = "positionMs"

    val COMMAND: SessionCommand = SessionCommand(ACTION, Bundle.EMPTY)
}
