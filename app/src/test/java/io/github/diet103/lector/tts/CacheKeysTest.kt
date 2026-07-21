package io.github.diet103.lector.tts

import io.github.diet103.lector.model.SpeakRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheKeysTest {

    private val base = SpeakRequest(
        text = "The rain in Spain stays mainly in the plain.",
        voiceId = "JBFqnCBsd6RMkjVDRZzb",
        modelId = "eleven_flash_v2_5",
        outputFormat = "mp3_44100_128"
    )

    @Test
    fun `same request always hashes to the same key`() {
        assertEquals(CacheKeys.forRequest(base), CacheKeys.forRequest(base.copy()))
    }

    @Test
    fun `key is 64 lowercase hex chars`() {
        assertTrue(CacheKeys.forRequest(base).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `every field participates in the key`() {
        val variants = listOf(
            base,
            base.copy(text = "The rain in Spain stays mainly in the plain!"),
            base.copy(voiceId = "21m00Tcm4TlvDq8ikWAM"),
            base.copy(modelId = "eleven_multilingual_v2"),
            base.copy(outputFormat = "mp3_22050_32")
        )
        val keys = variants.map(CacheKeys::forRequest)
        assertEquals(variants.size, keys.toSet().size)
    }
}
