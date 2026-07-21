package io.github.diet103.lector.entry

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SharedImageLoaderTest {

    private val screenshot: Uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `a shared image is recognised and its uri returned`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, screenshot)
        }

        assertEquals(screenshot, SharedImageLoader.imageUri(intent))
    }

    @Test
    fun `a text share is not an image share`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "just words")
        }

        assertNull(SharedImageLoader.imageUri(intent))
    }

    @Test
    fun `a selection is not an image share`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "words")

        assertNull(SharedImageLoader.imageUri(intent))
    }

    @Test
    fun `an image share with no stream extra yields nothing`() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg" }

        assertNull(SharedImageLoader.imageUri(intent))
    }

    /**
     * A smoke test, deliberately **not** advertised as a regression guard.
     *
     * `load` once collapsed the bounds pass into
     * `openInputStream(uri)?.use { decodeStream(…) } ?: return null`; on real Android
     * bounds-only decoding returns null by design, so that elvis fired for *every* image and the
     * OCR path always failed with "Couldn't open that image". Robolectric's BitmapFactory shadow
     * returns a non-null bitmap even in bounds-only mode, so it does not reproduce that — this
     * test was verified to still pass against the broken version. Bugs in this decode dance are
     * only catchable on a device.
     */
    @Test
    fun `a readable image decodes to a bitmap`() {
        val file = File.createTempFile("fixture", ".png").apply {
            writeBytes(Base64.getDecoder().decode(TINY_PNG))
            deleteOnExit()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()

        val bitmap = SharedImageLoader.load(context, Uri.fromFile(file))

        assertNotNull("load() returned null for a perfectly readable PNG", bitmap)
    }

    @Test
    fun `an unreadable uri yields null rather than throwing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = Uri.fromFile(File("/does/not/exist.png"))

        assertNull(runCatching { SharedImageLoader.load(context, missing) }.getOrNull())
    }

    @Test
    fun `an image already within the cap is not subsampled`() {
        assertEquals(1, SharedImageLoader.sampleSizeFor(1024, 768, maxDimension = 2048))
    }

    @Test
    fun `an image exactly at the cap is not subsampled`() {
        assertEquals(1, SharedImageLoader.sampleSizeFor(2048, 1000, maxDimension = 2048))
    }

    @Test
    fun `a phone screenshot halves once`() {
        assertEquals(2, SharedImageLoader.sampleSizeFor(1080, 2400, maxDimension = 2048))
    }

    @Test
    fun `an oversized image keeps halving until it fits`() {
        assertEquals(4, SharedImageLoader.sampleSizeFor(8000, 6000, maxDimension = 2048))
    }

    private companion object {
        /** An 8×8 opaque PNG, so the fixture is generated here rather than checked in. */
        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAE0lEQVR42mM4sWXBf3yYYWQoAABv4sbBqTlS3AAAAABJRU5ErkJggg=="
    }
}
