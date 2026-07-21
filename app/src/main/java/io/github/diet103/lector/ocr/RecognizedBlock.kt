package io.github.diet103.lector.ocr

/**
 * One block of recognized text with its position in the source image.
 *
 * Deliberately our own type rather than ML Kit's `Text.TextBlock`: it keeps [TextBlockAssembler]
 * a plain-JVM pure function, so the reading-order and junk-filter rules are unit-tested in CI
 * without an Android runtime or the ML Kit model (PLAN §8). [ScreenTextRecognizer] maps across.
 *
 * Coordinates are pixels in the (already downscaled) bitmap that was recognized, origin top-left.
 */
data class RecognizedBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val height: Int get() = bottom - top

    val centerY: Int get() = (top + bottom) / 2
}
