package io.github.diet103.lector.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule that decides whether the reader's tap moves the playhead — pure, so it is pinned
 * here rather than inferred from a device. The bug this replaced lived in the gap between two
 * components that each passed their own tests.
 */
class SeekDecisionTest {

    // Nothing else matters if the audio isn't all here: seeking would re-request it from byte zero
    // and bill the whole text again.
    @Test
    fun `a read that is still arriving is refused, whatever the player claims`() {
        assertEquals(
            SeekDecision.REFUSE,
            SeekDecision.decide(fullyCached = false, playerCanSeek = true)
        )
        assertEquals(
            SeekDecision.REFUSE,
            SeekDecision.decide(fullyCached = false, playerCanSeek = false)
        )
    }

    @Test
    fun `a cached read with a usable seek map just seeks`() {
        assertEquals(
            SeekDecision.SEEK,
            SeekDecision.decide(fullyCached = true, playerCanSeek = true)
        )
    }

    // The case that made tapping "jump to the beginning". The audio is all on disk, but this
    // playback was prepared while it was still streaming, so Media3 built a seek map it marked
    // non-seekable — and ProgressiveMediaPeriod silently rounds every seek on one of those to zero.
    // The map is never rebuilt for a prepared source, so the only fix is to prepare it again.
    @Test
    fun `a cached read prepared mid-stream is prepared again rather than seeked`() {
        assertEquals(
            SeekDecision.REPREPARE,
            SeekDecision.decide(fullyCached = true, playerCanSeek = false)
        )
    }
}
