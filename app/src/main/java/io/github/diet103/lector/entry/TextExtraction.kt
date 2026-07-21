package io.github.diet103.lector.entry

/** Result of pulling readable text out of an incoming intent (PLAN §5). */
sealed interface TextExtraction {

    /**
     * @param text the (possibly truncated) text to speak
     * @param truncated whether [text] was capped at a sentence boundary for cost (§2)
     * @param readOnly the PROCESS_TEXT read-only flag; `false` means an editable caller
     *   (Gmail compose, Keep). We never write back regardless — surfaced only so the contract
     *   is explicit and testable.
     */
    data class Extracted(
        val text: String,
        val truncated: Boolean,
        val readOnly: Boolean
    ) : TextExtraction

    data class Failed(val reason: ExtractionError) : TextExtraction
}

enum class ExtractionError {
    /** The extra was present but blank/whitespace-only. */
    EMPTY,

    /** An action or MIME type Lector doesn't handle. */
    UNSUPPORTED
}
