package io.github.diet103.lector.data

/**
 * Reversible stand-in for the Keystore (PLAN §8) — the real cipher needs a device, so the
 * instrumented suite covers that round trip and this covers [ApiKeyStore]'s logic.
 */
class FakeAesGcmCipher(
    private val failEncrypt: Boolean = false,
    private val failDecrypt: Boolean = false
) : AesGcmCipher {

    var decryptCalls = 0
        private set

    override fun encrypt(plaintext: String): ByteArray? =
        if (failEncrypt) null else (MAGIC + plaintext).toByteArray(Charsets.UTF_8)

    override fun decrypt(ciphertext: ByteArray): String? {
        decryptCalls++
        if (failDecrypt) return null
        val decoded = String(ciphertext, Charsets.UTF_8)
        return if (decoded.startsWith(MAGIC)) decoded.removePrefix(MAGIC) else null
    }

    private companion object {
        const val MAGIC = "fake:"
    }
}
