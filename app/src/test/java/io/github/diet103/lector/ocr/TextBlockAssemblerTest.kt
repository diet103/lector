package io.github.diet103.lector.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture layouts for the v0.1 single-column reading-order + junk-filter heuristic (PLAN §8).
 * Pure JVM — no Android, no ML Kit model — so these run on every push.
 *
 * Geometry convention: a 2000 px-tall image, which puts the status-bar strip at y ≤ 100.
 */
class TextBlockAssemblerTest {

    private val imageHeight = 2000

    private fun block(text: String, top: Int, bottom: Int, left: Int = 0, right: Int = 1000) =
        RecognizedBlock(text = text, left = left, top = top, right = right, bottom = bottom)

    @Test
    fun `blocks are read top to bottom whatever order they arrive in`() {
        val blocks = listOf(
            block("Third paragraph.", top = 900, bottom = 1000),
            block("First paragraph.", top = 200, bottom = 300),
            block("Second paragraph.", top = 500, bottom = 600)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("First paragraph.\nSecond paragraph.\nThird paragraph.", result)
    }

    @Test
    fun `blocks sharing a line are read left to right`() {
        val blocks = listOf(
            block("3h ago", top = 205, bottom = 255, left = 500, right = 800),
            block("u/someone", top = 200, bottom = 260, left = 0, right = 300)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("u/someone 3h ago", result)
    }

    @Test
    fun `the status bar strip is dropped`() {
        val blocks = listOf(
            block("9:41 5G 87%", top = 10, bottom = 60),
            block("The actual post body.", top = 300, bottom = 400)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("The actual post body.", result)
    }

    @Test
    fun `a clock in the body is not mistaken for the status bar`() {
        val blocks = listOf(
            block("Meet me at 9:41 tomorrow.", top = 300, bottom = 400)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("Meet me at 9:41 tomorrow.", result)
    }

    @Test
    fun `a short all-caps line at the very top survives — a crop, not a status bar`() {
        val blocks = listOf(
            block("ALERT", top = 10, bottom = 60),
            block("Something happened.", top = 300, bottom = 400)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("ALERT\nSomething happened.", result)
    }

    @Test
    fun `tiny fragments and stray glyphs are dropped`() {
        val blocks = listOf(
            block("•", top = 300, bottom = 340),
            block("I", top = 400, bottom = 440),
            block("Real sentence here.", top = 500, bottom = 600),
            block("<", top = 700, bottom = 740)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("Real sentence here.", result)
    }

    @Test
    fun `line wraps inside a block become spaces so the voice does not pause mid-sentence`() {
        val blocks = listOf(
            block("A sentence that the\nrenderer wrapped\nacross three lines.", top = 300, bottom = 500)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("A sentence that the renderer wrapped across three lines.", result)
    }

    @Test
    fun `an image with nothing but chrome assembles to nothing`() {
        val blocks = listOf(
            block("9:41", top = 10, bottom = 60, left = 0, right = 120),
            block("87%", top = 10, bottom = 60, left = 880, right = 1000),
            block("•", top = 700, bottom = 740)
        )

        val result = TextBlockAssembler.assemble(blocks, imageHeight)

        assertEquals("", result)
    }

    @Test
    fun `no blocks at all assembles to nothing`() {
        assertEquals("", TextBlockAssembler.assemble(emptyList(), imageHeight))
    }
}
