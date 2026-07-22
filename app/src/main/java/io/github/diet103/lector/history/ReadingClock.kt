package io.github.diet103.lector.history

/**
 * Maps between a position in the text and a position in the audio, both ways: forwards to highlight
 * the words being spoken, backwards to start playing from a word someone tapped.
 *
 * An interface because there are two possible sources for this. Today it's estimated from the audio
 * duration ([EstimatedReadingClock]); ElevenLabs also has a `/stream/with-timestamps` endpoint
 * giving exact per-character times, but that returns base64-in-JSON rather than MP3 and would mean
 * rewriting the streaming layer that guards billing. The estimate is good to within a word or two,
 * so the UI is built against this interface and the exact source can drop in later without the
 * reader knowing.
 */
interface ReadingClock {

    /** Where in the audio the character at [charOffset] is spoken. */
    fun positionFor(charOffset: Int): Long

    /** Which character is being spoken at [positionMs]. */
    fun charOffsetFor(positionMs: Long): Int
}

/**
 * Estimates timings by distributing the audio duration across the text.
 *
 * Not a flat characters-per-second: text-to-speech pauses at punctuation, and a naive linear map
 * drifts badly on anything with dialogue or short paragraphs. Weighting the pause characters as if
 * they were several letters long keeps a few minutes of reading inside a sentence's width, which is
 * all the highlight needs — the UI tints the whole sentence and only emphasises the word, so a
 * small error stays invisible.
 *
 * Pure, and unit-tested directly.
 */
class EstimatedReadingClock(
    private val text: String,
    private val durationMs: Long
) : ReadingClock {

    /**
     * `weights[i]` is the total weight of everything before character `i`, so it has one more
     * entry than the text has characters and is non-decreasing — which is what makes the reverse
     * lookup a binary search.
     */
    private val cumulative: LongArray = LongArray(text.length + 1).also { sums ->
        var running = 0L
        for (i in text.indices) {
            running += weightOf(text[i])
            sums[i + 1] = running
        }
    }

    private val total: Long get() = cumulative.last()

    override fun positionFor(charOffset: Int): Long {
        if (durationMs <= 0 || total == 0L) return 0
        val clamped = charOffset.coerceIn(0, text.length)
        return durationMs * cumulative[clamped] / total
    }

    override fun charOffsetFor(positionMs: Long): Int {
        if (durationMs <= 0 || total == 0L) return 0
        if (positionMs <= 0) return 0
        if (positionMs >= durationMs) return text.length

        val targetWeight = total * positionMs / durationMs
        val found = cumulative.binarySearch(targetWeight)
        // An exact hit lands on the boundary *after* that character; a miss returns the insertion
        // point, whose predecessor is the character currently being spoken.
        val index = if (found >= 0) found else -found - 2
        return index.coerceIn(0, text.length)
    }

    private companion object {
        /**
         * How many characters' worth of time a pause is worth. Rough by design — these came from
         * listening, not from measurement, and the sentence-level highlight absorbs the error.
         */
        fun weightOf(c: Char): Long = when (c) {
            '.', '!', '?' -> 6
            ',', ';', ':' -> 3
            '\n' -> 8
            else -> 1
        }
    }
}

/**
 * Where the sentence and word containing a character begin and end — what the reader tints and
 * emphasises. Pure, so the boundary rules are unit-tested rather than eyeballed on a device.
 */
object TextSpans {

    private const val SENTENCE_ENDINGS = ".!?\n"

    /**
     * The sentence containing [offset], including its terminating punctuation. Trailing spaces are
     * left out so the tint doesn't run into the next sentence.
     */
    fun sentenceAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length - 1)

        var start = at
        while (start > 0 && text[start - 1] !in SENTENCE_ENDINGS) start--
        // Step over the whitespace that follows the previous sentence's full stop.
        while (start < at && text[start].isWhitespace()) start++

        var end = at
        while (end < text.length && text[end] !in SENTENCE_ENDINGS) end++
        if (end < text.length) end++

        return start until end.coerceAtLeast(start + 1)
    }

    /**
     * The word containing [offset]. Landing on whitespace between words returns the word that
     * follows, so tapping in a gap starts the phrase you were pointing at rather than the one you
     * just heard.
     */
    fun wordAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        var at = offset.coerceIn(0, text.length - 1)

        if (text[at].isWhitespace()) {
            val next = (at until text.length).firstOrNull { !text[it].isWhitespace() }
            at = next ?: (0..at).last { !text[it].isWhitespace() }
        }

        var start = at
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = at
        while (end < text.length && isWordChar(text[end])) end++

        return start until end.coerceAtLeast(start + 1)
    }

    /** Apostrophes count, so "don't" is one word rather than two. */
    private fun isWordChar(c: Char): Boolean = !c.isWhitespace() && c != '"'
}
