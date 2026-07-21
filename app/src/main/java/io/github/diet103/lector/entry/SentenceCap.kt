package io.github.diet103.lector.entry

/**
 * Cost guard (PLAN §2): cap text at a sentence boundary so a runaway selection can't bill the
 * whole thing. Keeps as many whole sentences as fit under [maxChars]; if a single sentence
 * already overruns the cap, falls back to a hard cut. Truncation is always reported so the
 * caller can announce it. Pure — no Android, unit-tested directly.
 */
object SentenceCap {

    data class Result(val text: String, val truncated: Boolean)

    private const val SENTENCE_ENDINGS = ".!?"

    fun apply(text: String, maxChars: Int): Result {
        if (text.length <= maxChars) return Result(text, truncated = false)

        val window = text.substring(0, maxChars)
        val lastEnding = window.indexOfLast { it in SENTENCE_ENDINGS }
        val cut = if (lastEnding >= 0) lastEnding + 1 else maxChars
        return Result(text.substring(0, cut).trimEnd(), truncated = true)
    }
}
