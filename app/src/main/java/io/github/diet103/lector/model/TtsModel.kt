package io.github.diet103.lector.model

/**
 * The synthesis models Lector offers (PLAN §6 P6).
 *
 * Caps are the real `maximum_text_length_per_request` values from `/v1/models`, not guesses, and
 * they matter: they bound the user's cap-override slider. Picking Multilingual with a 40k cap
 * still set would fail at the API rather than in Lector, which reads as our bug.
 *
 * ElevenLabs charges the same per character for all of these, so the tradeoff is genuinely
 * latency versus quality — no "cheaper model" to recommend.
 */
enum class TtsModel(
    val id: String,
    val label: String,
    val maxChars: Int,
    val blurb: String
) {
    FLASH(
        id = "eleven_flash_v2_5",
        label = "Flash v2.5",
        maxChars = 40_000,
        blurb = "Starts fastest. Best for reading things off your screen."
    ),
    TURBO(
        id = "eleven_turbo_v2_5",
        label = "Turbo v2.5",
        maxChars = 40_000,
        blurb = "A little slower to start, a little richer."
    ),
    MULTILINGUAL(
        id = "eleven_multilingual_v2",
        label = "Multilingual v2",
        maxChars = 10_000,
        blurb = "Best quality and strongest on non-English text. Slowest to start."
    );

    companion object {
        val DEFAULT = FLASH

        fun fromId(id: String?): TtsModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Audio format. The low option roughly quarters the bytes, so the first audio arrives sooner on a
 * weak connection at an audible cost — the tradeoff the settings screen states in one line.
 */
enum class TtsFormat(val id: String, val label: String, val blurb: String) {
    STANDARD(
        id = "mp3_44100_128",
        label = "Standard",
        blurb = "Full quality. The default."
    ),
    FAST_START(
        id = "mp3_22050_32",
        label = "Faster start",
        blurb = "Noticeably rougher, but starts sooner on a weak connection."
    );

    companion object {
        val DEFAULT = STANDARD

        fun fromId(id: String?): TtsFormat = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
