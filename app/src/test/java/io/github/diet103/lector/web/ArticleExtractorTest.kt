package io.github.diet103.lector.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — jsoup is plain Java, so no Android runtime is needed. */
class ArticleExtractorTest {

    private fun paragraphs(count: Int, word: String = "sentence") =
        (1..count).joinToString("") { "<p>This is a full $word number $it of the article body.</p>" }

    @Test
    fun `an article element wins over surrounding chrome`() {
        val html = """
            <html><head><title>The Headline</title></head><body>
              <nav><p>Home About Contact Subscribe Login Register Help Terms Privacy</p></nav>
              <article>${paragraphs(6)}</article>
              <footer><p>Copyright some company all rights reserved worldwide forever</p></footer>
            </body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html)!!

        assertEquals("The Headline", article.title)
        assertTrue(article.text.contains("number 1"))
        assertFalse("navigation leaked in", article.text.contains("Subscribe"))
        assertFalse("footer leaked in", article.text.contains("Copyright"))
    }

    @Test
    fun `scripts and styles never reach the reader`() {
        val html = """
            <html><body><article>
              <script>var tracking = "do not read me aloud";</script>
              <style>.x { color: red }</style>
              ${paragraphs(6)}
            </article></body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html)!!

        assertFalse(article.text.contains("tracking"))
        assertFalse(article.text.contains("color"))
    }

    @Test
    fun `a page with no article element falls back to the densest block`() {
        val html = """
            <html><head><title>Fallback</title></head><body>
              <div id="sidebar"><p>Short aside.</p></div>
              <div id="content">${paragraphs(8)}</div>
            </body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html)!!

        assertTrue(article.text.contains("number 8"))
        assertFalse(article.text.contains("Short aside"))
    }

    @Test
    fun `headings are kept and read in document order`() {
        val html = """
            <html><body><article>
              <h1>Chapter One</h1>${paragraphs(6)}
            </article></body></html>
        """.trimIndent()

        val text = ArticleExtractor.extract(html)!!.text

        assertTrue(text.startsWith("Chapter One"))
    }

    @Test
    fun `og-title is preferred over the tab title`() {
        val html = """
            <html><head><title>Site Name | Section | Article</title>
            <meta property="og:title" content="The Real Headline"></head>
            <body><article>${paragraphs(6)}</article></body></html>
        """.trimIndent()

        assertEquals("The Real Headline", ArticleExtractor.extract(html)!!.title)
    }

    /** A nav-only or paywalled shell must fail, not produce a mouthful of menu items. */
    @Test
    fun `a page with no real prose yields nothing`() {
        val html = """
            <html><head><title>Login</title></head><body>
              <div><p>Sign in</p><p>Register</p><p>Help</p></div>
            </body></html>
        """.trimIndent()

        assertNull(ArticleExtractor.extract(html))
    }

    @Test
    fun `malformed html does not throw`() {
        assertNull(ArticleExtractor.extract("<html><body><p>unclosed"))
    }

    @Test
    fun `repeated boilerplate lines are not read twice`() {
        val html = """
            <html><body><article>
              <p>Share this article</p>${paragraphs(6)}<p>Share this article</p>
            </article></body></html>
        """.trimIndent()

        val text = ArticleExtractor.extract(html)!!.text

        assertEquals(1, text.lines().count { it == "Share this article" })
    }
}
