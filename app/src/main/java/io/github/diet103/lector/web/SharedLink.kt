package io.github.diet103.lector.web

/**
 * Decides whether a text share is really a *link* share (PLAN backlog: URL-content fetch).
 *
 * Share sheets send links in a few shapes: a bare URL, or a title followed by a URL. If Lector
 * just spoke those, you'd hear "h-t-t-p-s colon slash slash" read out, which is useless. But a
 * paragraph that merely *contains* a link is still a paragraph and should be read as-is — so a
 * share only counts as a link when there's exactly one URL and barely anything else around it.
 *
 * Pure — unit-tested directly.
 */
object SharedLink {

    /** Room for a shared page title alongside the URL, but not for real prose. */
    private const val MAX_SURROUNDING_CHARS = 160

    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Hosts that refuse anonymous reads, so fetching would fail confusingly. */
    private val WALLED_HOSTS = setOf(
        "reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com",
        "sh.reddit.com", "np.reddit.com", "redd.it", "amp.reddit.com"
    )

    /** The single shared URL, or `null` when this share should be read as plain text. */
    fun detect(text: String): String? {
        val matches = URL_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.size != 1) return null

        val url = matches.first().trimEnd('.', ',', ')', ']', '"', '\'')
        val remainder = text.replace(matches.first(), "").trim()
        if (remainder.length > MAX_SURROUNDING_CHARS) return null

        return url
    }

    /**
     * True for sites that serve anonymous fetches a login wall rather than the content. Verified
     * 2026-07-21: reddit `.json` returns 403 and `old.reddit.com` serves a "Welcome to Reddit"
     * interstitial, so there is nothing to extract without an account.
     */
    fun isWalledOff(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host in WALLED_HOSTS || WALLED_HOSTS.any { host.endsWith(".$it") }
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()
    }.getOrNull()
}
