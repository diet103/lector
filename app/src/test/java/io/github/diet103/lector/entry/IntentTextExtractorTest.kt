package io.github.diet103.lector.entry

import android.content.Intent
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IntentTextExtractorTest {

    @Test
    fun `process text selection is extracted, editable caller flagged`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "The quick brown fox.")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        }

        val result = IntentTextExtractor.extract(intent)

        assertEquals(TextExtraction.Extracted("The quick brown fox.", false, readOnly = false), result)
    }

    @Test
    fun `read-only process text carries the read-only flag`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, "read only selection")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        val result = IntentTextExtractor.extract(intent) as TextExtraction.Extracted

        assertTrue(result.readOnly)
    }

    @Test
    fun `a styled selection is flattened to plain text`() {
        val styled = SpannableString("bold and normal").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, 0)
        }
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, styled)

        val result = IntentTextExtractor.extract(intent) as TextExtraction.Extracted

        assertEquals("bold and normal", result.text)
    }

    @Test
    fun `shared text-plain is extracted`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "shared paragraph")
        }

        val result = IntentTextExtractor.extract(intent) as TextExtraction.Extracted

        assertEquals("shared paragraph", result.text)
    }

    @Test
    fun `blank selection fails as EMPTY`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "   \n  ")

        val result = IntentTextExtractor.extract(intent)

        assertEquals(TextExtraction.Failed(ExtractionError.EMPTY), result)
    }

    @Test
    fun `a non-text share is unsupported`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "caption that should be ignored")
        }

        val result = IntentTextExtractor.extract(intent)

        assertEquals(TextExtraction.Failed(ExtractionError.UNSUPPORTED), result)
    }

    @Test
    fun `an unrelated action is unsupported`() {
        val result = IntentTextExtractor.extract(Intent(Intent.ACTION_VIEW))

        assertEquals(TextExtraction.Failed(ExtractionError.UNSUPPORTED), result)
    }

    @Test
    fun `over-cap selection is truncated and flagged`() {
        val longText = "This is a sentence. ".repeat(400) // 8000 chars, well over 5000
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, longText)

        val result = IntentTextExtractor.extract(intent) as TextExtraction.Extracted

        assertTrue(result.truncated)
        assertTrue(result.text.length <= IntentTextExtractor.DEFAULT_MAX_CHARS)
        assertTrue(result.text.endsWith("."))
    }
}
