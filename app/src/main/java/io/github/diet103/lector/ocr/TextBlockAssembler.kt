package io.github.diet103.lector.ocr

/**
 * Unordered OCR blocks → one readable string (PLAN §4; risk #5).
 *
 * ML Kit hands back blocks in no guaranteed reading order, mixed in with whatever screen chrome
 * happened to be in the shot. v0.1 is a deliberately simple **single-column** heuristic:
 *
 *  1. flatten each block's internal line wraps to spaces — they're layout artifacts, and a hard
 *     newline mid-sentence makes the TTS voice pause in the wrong place;
 *  2. drop tiny fragments (icons and stray glyphs OCR'd as text);
 *  3. drop the status-bar strip — but only a block at the very top that also carries the
 *     giveaway (a clock or a battery percentage), so a *cropped* screenshot whose first line is
 *     real content survives;
 *  4. group what's left into rows by vertical overlap, order rows top-to-bottom and each row
 *     left-to-right.
 *
 * Multi-column and overlay-heavy layouts are knowingly out of scope: cropping the screenshot is
 * the user-side power move, and it works because a crop is just a smaller image (risk #5).
 *
 * Pure — no Android, no ML Kit. Unit-tested directly in CI over fixture layouts.
 */
object TextBlockAssembler {

    /** Fraction of image height in which a block may be considered status-bar chrome. */
    private const val STATUS_BAR_STRIP = 0.05

    private val CLOCK = Regex("""\d{1,2}:\d{2}""")
    private val BATTERY = Regex("""\d{1,3}\s*%""")
    private val LINE_BREAK = Regex("""\s*\n\s*""")
    private val WHITESPACE = Regex("""\s+""")

    fun assemble(blocks: List<RecognizedBlock>, imageHeight: Int): String {
        val stripBottom = (imageHeight * STATUS_BAR_STRIP).toInt()

        val kept = blocks
            .map { it.copy(text = it.text.replace(LINE_BREAK, " ").trim()) }
            .filterNot { isTinyFragment(it.text) }
            .filterNot { it.bottom <= stripBottom && isStatusBarChrome(it.text) }

        return groupIntoRows(kept).joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }

    private fun isTinyFragment(text: String): Boolean =
        text.length < 2 || text.none { it.isLetterOrDigit() }

    private fun isStatusBarChrome(text: String): Boolean {
        val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        // Every token has to look like chrome AND one has to be the giveaway. A short all-caps
        // word on its own is not enough — that's a cropped headline, not a status bar.
        return tokens.all { isChromeToken(it) } &&
            tokens.any { CLOCK.matches(it) || BATTERY.matches(it) }
    }

    private fun isChromeToken(token: String): Boolean = when {
        CLOCK.matches(token) -> true
        BATTERY.matches(token) -> true
        token.none { it.isLetterOrDigit() } -> true
        // Carrier and radio labels: LTE, 5G, WIFI, AM, PM.
        else -> token.length <= 5 && token.none { it.isLowerCase() }
    }

    /**
     * Blocks sorted top-to-bottom, then greedily merged into rows. Two blocks share a row only if
     * each one's vertical centre falls inside the other's span — mutual containment, so a tall
     * paragraph doesn't swallow the short block that follows it.
     */
    private fun groupIntoRows(blocks: List<RecognizedBlock>): List<List<RecognizedBlock>> {
        val rows = mutableListOf<MutableList<RecognizedBlock>>()
        for (block in blocks.sortedBy { it.top }) {
            val current = rows.lastOrNull()
            if (current != null && current.any { sharesLine(it, block) }) {
                current.add(block)
            } else {
                rows.add(mutableListOf(block))
            }
        }
        return rows
    }

    private fun sharesLine(a: RecognizedBlock, b: RecognizedBlock): Boolean =
        a.centerY in b.top..b.bottom && b.centerY in a.top..a.bottom
}
