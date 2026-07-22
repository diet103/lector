package io.github.diet103.lector.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — no Android, no network. */
class SharedLinkTest {

    @Test
    fun `a bare url is a link share`() {
        assertEquals(
            "https://example.com/article",
            SharedLink.detect("https://example.com/article")
        )
    }

    @Test
    fun `surrounding whitespace does not hide the link`() {
        assertEquals("https://example.com/a", SharedLink.detect("  https://example.com/a \n"))
    }

    @Test
    fun `a shared title plus url is still a link share`() {
        val shared = "How the thing works\nhttps://example.com/how-it-works"

        assertEquals("https://example.com/how-it-works", SharedLink.detect(shared))
    }

    /** The important negative: real prose that happens to cite a source stays prose. */
    @Test
    fun `a paragraph containing a url is read as text, not fetched`() {
        val prose = "I've been reading about this for weeks and the clearest explanation I found " +
            "was on https://example.com/explainer which lays out the whole argument in a way " +
            "that finally made it click for me, especially the part about incentives."

        assertNull(SharedLink.detect(prose))
    }

    @Test
    fun `two urls are ambiguous so nothing is fetched`() {
        val shared = "https://example.com/one and https://example.com/two"

        assertNull(SharedLink.detect(shared))
    }

    @Test
    fun `plain text with no url is not a link`() {
        assertNull(SharedLink.detect("Just some words I selected."))
    }

    @Test
    fun `trailing punctuation is trimmed off the url`() {
        assertEquals("https://example.com/a", SharedLink.detect("(https://example.com/a)"))
    }

    @Test
    fun `reddit hosts are known to be walled off`() {
        assertTrue(SharedLink.isWalledOff("https://www.reddit.com/r/x/comments/1/"))
        assertTrue(SharedLink.isWalledOff("https://old.reddit.com/r/x/"))
        assertTrue(SharedLink.isWalledOff("https://redd.it/abc123"))
    }

    @Test
    fun `ordinary sites are not walled off`() {
        assertFalse(SharedLink.isWalledOff("https://example.com/a"))
        assertFalse(SharedLink.isWalledOff("https://en.wikipedia.org/wiki/Otter"))
    }

    /** Guard against a naive substring check treating any host containing "reddit.com" as Reddit. */
    @Test
    fun `a lookalike host is not mistaken for reddit`() {
        assertFalse(SharedLink.isWalledOff("https://notreddit.com.example.org/post"))
    }
}
