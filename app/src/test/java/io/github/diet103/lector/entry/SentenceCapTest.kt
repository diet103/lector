package io.github.diet103.lector.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCapTest {

    @Test
    fun `text within the cap is returned unchanged`() {
        val text = "Short and sweet."
        val result = SentenceCap.apply(text, maxChars = 100)
        assertEquals(text, result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `text exactly at the cap is not truncated`() {
        val text = "x".repeat(50)
        val result = SentenceCap.apply(text, maxChars = 50)
        assertEquals(text, result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `over-cap text cuts at the last sentence boundary within the window`() {
        val text = "One sentence. Two sentence. Three sentence that runs past the cap entirely."
        val result = SentenceCap.apply(text, maxChars = 30)

        assertTrue(result.truncated)
        assertEquals("One sentence. Two sentence.", result.text)
        assertTrue(result.text.length <= 30)
    }

    @Test
    fun `over-cap text with no sentence boundary falls back to a hard cut`() {
        val text = "wordy".repeat(50) // no . ! or ?, no spaces to trim
        val result = SentenceCap.apply(text, maxChars = 20)

        assertTrue(result.truncated)
        assertEquals(20, result.text.length)
    }

    @Test
    fun `question and exclamation marks count as sentence boundaries`() {
        val text = "Really? Yes! And then a very long trailing clause that exceeds the cap by a lot."
        val result = SentenceCap.apply(text, maxChars = 12)

        assertTrue(result.truncated)
        assertEquals("Really? Yes!", result.text)
    }
}
