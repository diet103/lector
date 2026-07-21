package io.github.diet103.lector.entry

import android.content.Intent

/**
 * Pure `Intent → TextExtraction` (PLAN §5). Handles the two P3 text entry points:
 *  - `ACTION_PROCESS_TEXT` (the "Read aloud" selection-toolbar action), carrying the selected
 *    `CharSequence` and a read-only flag.
 *  - `ACTION_SEND` `text/plain` (the share sheet).
 *
 * A styled selection arrives as a `Spanned` CharSequence; `toString()` flattens it to plain
 * text. Blank text and anything else fail with a typed [ExtractionError] the scrim can phrase.
 */
object IntentTextExtractor {

    const val DEFAULT_MAX_CHARS = 5000

    fun extract(intent: Intent, maxChars: Int = DEFAULT_MAX_CHARS): TextExtraction {
        val (raw, readOnly) = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) to
                    intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) {
                    return TextExtraction.Failed(ExtractionError.UNSUPPORTED)
                }
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT) to true
            }

            else -> return TextExtraction.Failed(ExtractionError.UNSUPPORTED)
        }

        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return TextExtraction.Failed(ExtractionError.EMPTY)

        val capped = SentenceCap.apply(text, maxChars)
        return TextExtraction.Extracted(capped.text, capped.truncated, readOnly)
    }
}
