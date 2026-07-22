package io.github.diet103.lector.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * HTML → the words a person actually wanted to hear (PLAN backlog: article extraction).
 *
 * A deliberately small readability heuristic rather than a port of one: strip the furniture
 * (script, nav, header, footer, asides, comment blocks), prefer a semantic container if the page
 * has one, otherwise pick the element with the most paragraph text, and read its paragraphs and
 * headings in document order.
 *
 * Pure — jsoup is plain Java, so this is unit-tested on fixture HTML with no Android runtime.
 */
object ArticleExtractor {

    /** Below this, we almost certainly grabbed navigation rather than an article. */
    private const val MIN_ARTICLE_CHARS = 200

    private val FURNITURE = listOf(
        "script", "style", "noscript", "nav", "header", "footer", "aside", "form",
        "iframe", "svg", "button", "figure figcaption", "[aria-hidden=true]",
        ".advertisement", ".ad", ".cookie", ".newsletter", ".related", ".comments", "#comments"
    )

    private val CANDIDATE_SELECTORS = listOf(
        "article", "main", "[role=main]", ".post-content", ".article-body", ".entry-content"
    )

    data class Article(val title: String?, val text: String)

    fun extract(html: String, baseUrl: String = ""): Article? {
        val document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        FURNITURE.forEach { selector -> document.select(selector).remove() }

        val root = bestContainer(document) ?: return null
        val text = readableTextOf(root)
        if (text.length < MIN_ARTICLE_CHARS) return null

        return Article(title = titleOf(document), text = text)
    }

    private fun bestContainer(document: Document): Element? {
        CANDIDATE_SELECTORS.forEach { selector ->
            val candidate = document.select(selector).maxByOrNull { paragraphWeight(it) }
            if (candidate != null && paragraphWeight(candidate) >= MIN_ARTICLE_CHARS) return candidate
        }
        // No semantic container: take whichever block holds the most paragraph text.
        return document.select("div, section")
            .maxByOrNull { paragraphWeight(it) }
            ?.takeIf { paragraphWeight(it) >= MIN_ARTICLE_CHARS }
            ?: document.body()
    }

    /** Weight by paragraph text only, so a sidebar full of links can't win on raw length. */
    private fun paragraphWeight(element: Element): Int =
        element.select("p").sumOf { it.text().length }

    private fun readableTextOf(root: Element): String =
        root.select("h1, h2, h3, h4, p, li, blockquote")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .trim()

    private fun titleOf(document: Document): String? =
        document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: document.title().takeIf { it.isNotBlank() }
}
