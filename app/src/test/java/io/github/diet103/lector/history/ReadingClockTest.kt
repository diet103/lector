package io.github.diet103.lector.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimate the reader highlights and seeks by. Pure JVM — no Robolectric needed, which is the
 * point of keeping the timing model out of the UI.
 */
class ReadingClockTest {

    private val plain = "abcdefghij"

    @Test
    fun `an even text maps proportionally`() {
        val clock = EstimatedReadingClock(plain, durationMs = 1000)

        assertEquals(0L, clock.positionFor(0))
        assertEquals(500L, clock.positionFor(5))
        assertEquals(1000L, clock.positionFor(10))
    }

    @Test
    fun `offsets round-trip back to themselves`() {
        val clock = EstimatedReadingClock(plain, durationMs = 1000)

        for (offset in 0 until plain.length) {
            val position = clock.positionFor(offset)
            assertEquals("offset $offset", offset, clock.charOffsetFor(position))
        }
    }

    // The whole reason this isn't a flat characters-per-second map: TTS pauses at a full stop, so
    // an unweighted estimate drifts forward across every sentence break.
    @Test
    fun `a full stop takes longer than a letter`() {
        val clock = EstimatedReadingClock("ab.cd", durationMs = 1000)

        val beforeStop = clock.positionFor(2)
        val afterStop = clock.positionFor(3)
        val betweenLetters = clock.positionFor(1) - clock.positionFor(0)

        assertTrue("a stop should outweigh a letter", afterStop - beforeStop > betweenLetters)
    }

    @Test
    fun `pause weights are ordered - newline over sentence over clause over letter`() {
        val spans = listOf("a\nb", "a.b", "a,b", "aab").map { text ->
            val clock = EstimatedReadingClock(text, durationMs = 3000)
            clock.positionFor(2) - clock.positionFor(1)
        }

        assertEquals("weights should be strictly ordered", spans.sortedDescending(), spans)
    }

    @Test
    fun `out of range offsets clamp instead of throwing`() {
        val clock = EstimatedReadingClock(plain, durationMs = 1000)

        assertEquals(0L, clock.positionFor(-5))
        assertEquals(1000L, clock.positionFor(999))
    }

    @Test
    fun `positions outside the audio clamp to the ends of the text`() {
        val clock = EstimatedReadingClock(plain, durationMs = 1000)

        assertEquals(0, clock.charOffsetFor(-1))
        assertEquals(0, clock.charOffsetFor(0))
        assertEquals(plain.length, clock.charOffsetFor(1000))
        assertEquals(plain.length, clock.charOffsetFor(99_999))
    }

    // A read that never finished has no duration, and the reader must degrade rather than divide
    // by zero.
    @Test
    fun `an unknown duration maps everything to zero`() {
        val clock = EstimatedReadingClock(plain, durationMs = 0)

        assertEquals(0L, clock.positionFor(5))
        assertEquals(0, clock.charOffsetFor(500))
    }

    @Test
    fun `empty text does not throw`() {
        val clock = EstimatedReadingClock("", durationMs = 1000)

        assertEquals(0L, clock.positionFor(0))
        assertEquals(0, clock.charOffsetFor(500))
    }

    @Test
    fun `the estimate stays inside its sentence over a long read`() {
        // ~4 minutes of mixed-length sentences, the case the sentence tint has to absorb.
        val text = buildString {
            repeat(60) { i ->
                append("This is sentence number $i, and it carries a clause. ")
                if (i % 5 == 0) append("Short one. ")
                if (i % 7 == 0) append("\n")
            }
        }
        val clock = EstimatedReadingClock(text, durationMs = 240_000)

        // Every reported offset must land inside the sentence that contains it — which is the
        // actual guarantee the reader relies on, rather than exact word accuracy.
        for (positionMs in 0L until 240_000L step 5_000) {
            val offset = clock.charOffsetFor(positionMs)
            val sentence = TextSpans.sentenceAt(text, offset)
            assertTrue("offset $offset at ${positionMs}ms", offset in sentence)
        }
    }
}

class TextSpansTest {

    private val text = "First one. Second here! Third?"

    @Test
    fun `sentenceAt covers the sentence including its full stop`() {
        assertEquals("First one.", text.substring(TextSpans.sentenceAt(text, 3)))
        assertEquals("Second here!", text.substring(TextSpans.sentenceAt(text, 15)))
        assertEquals("Third?", text.substring(TextSpans.sentenceAt(text, 26)))
    }

    @Test
    fun `sentenceAt does not bleed into the leading space of the next sentence`() {
        // Offset 10 is the '.' terminating the first sentence.
        assertEquals("First one.", text.substring(TextSpans.sentenceAt(text, 9)))
        assertEquals("Second here!", text.substring(TextSpans.sentenceAt(text, 11)))
    }

    @Test
    fun `sentenceAt treats a newline as a break`() {
        val lines = "A heading\nAnd the body."
        assertEquals("A heading\n", lines.substring(TextSpans.sentenceAt(lines, 2)))
        assertEquals("And the body.", lines.substring(TextSpans.sentenceAt(lines, 12)))
    }

    @Test
    fun `sentenceAt handles a text with no terminator at all`() {
        val bare = "no punctuation here"
        assertEquals(bare, bare.substring(TextSpans.sentenceAt(bare, 5)))
    }

    @Test
    fun `wordAt finds the word under the offset`() {
        assertEquals("First", text.substring(TextSpans.wordAt(text, 0)))
        assertEquals("First", text.substring(TextSpans.wordAt(text, 4)))
        assertEquals("one.", text.substring(TextSpans.wordAt(text, 7)))
    }

    // Tapping in the gap between words should start the phrase you were pointing at, not the one
    // you just heard.
    @Test
    fun `wordAt on whitespace picks the following word`() {
        assertEquals("one.", text.substring(TextSpans.wordAt(text, 5)))
    }

    @Test
    fun `wordAt on trailing whitespace falls back to the previous word`() {
        val trailing = "last word   "
        assertEquals("word", trailing.substring(TextSpans.wordAt(trailing, 11)))
    }

    @Test
    fun `an apostrophe keeps a contraction together`() {
        val contracted = "it doesn't split"
        assertEquals("doesn't", contracted.substring(TextSpans.wordAt(contracted, 5)))
    }

    @Test
    fun `empty text yields empty ranges rather than throwing`() {
        assertTrue(TextSpans.wordAt("", 0).isEmpty())
        assertTrue(TextSpans.sentenceAt("", 0).isEmpty())
    }

    @Test
    fun `out of range offsets clamp`() {
        assertEquals("Third?", text.substring(TextSpans.sentenceAt(text, 9999)))
        assertEquals("First", text.substring(TextSpans.wordAt(text, -3)))
    }
}
