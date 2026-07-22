package io.github.diet103.lector.history

import io.github.diet103.lector.model.SpeakRequest

/**
 * One thing Lector has read aloud (PLAN §6, v0.2).
 *
 * [key] is the same `sha256(text|voice|model|format)` the audio cache uses
 * ([CacheKeys][io.github.diet103.lector.tts.CacheKeys]), which buys three things: reading the same
 * text twice updates one row instead of making two, the row carries everything needed to rebuild
 * its [SpeakRequest], and "is replaying this free?" is answerable by asking the cache about the
 * same key.
 *
 * **Never holds a screenshot.** An image read stores the recognized text and [ReadSource.SCREENSHOT]
 * — never the bitmap, never the `content://` URI it came from.
 */
data class HistoryEntry(
    val key: String,
    val text: String,
    val title: String?,
    val source: ReadSource,
    val sourceUrl: String?,
    val voiceId: String,
    val modelId: String,
    val outputFormat: String,
    val createdAt: Long,
    val lastReadAt: Long,
    /** Filled in when a read reaches the end; `null` while it has never been played through. */
    val durationMs: Long?,
    /** Bytes of audio the completed read produced — how "fully cached" is decided. */
    val audioBytes: Long?
) {

    /** Rebuilds the request, so a replay resolves through the normal registry → resolver chain. */
    fun toSpeakRequest(): SpeakRequest = SpeakRequest(
        text = text,
        voiceId = voiceId,
        modelId = modelId,
        outputFormat = outputFormat
    )

    /** What the list shows when there's no title: enough to recognise, not the whole read. */
    fun snippet(max: Int = SNIPPET_CHARS): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    private companion object {
        const val SNIPPET_CHARS = 120
        val WHITESPACE = Regex("""\s+""")
    }
}

/** How the text got to Lector. Stored as the enum name, so adding cases is backward-compatible. */
enum class ReadSource {
    /** The "Read aloud (Lector)" selection-toolbar action. */
    SELECTION,

    /** Plain text through the share sheet. */
    SHARED_TEXT,

    /** A shared link whose page was fetched and stripped down to the article. */
    LINK,

    /** A shared image, read on-device by ML Kit. The image itself is never stored. */
    SCREENSHOT,

    /** The try-it box on Home. */
    TRY_IT;

    companion object {
        /** Unknown names read back as [SHARED_TEXT] rather than throwing on a downgrade. */
        fun fromName(name: String?): ReadSource =
            entries.firstOrNull { it.name == name } ?: SHARED_TEXT
    }
}
