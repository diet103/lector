package io.github.diet103.lector.data

/**
 * Symmetric encryption seam for the stored API key (PLAN §5).
 *
 * An interface purely so [ApiKeyStore]'s logic — including its corrupted-ciphertext recovery —
 * is testable on the JVM: the real implementation is backed by the Android Keystore, which needs
 * a device. Both directions work in bytes so callers never have to think about encodings.
 */
interface AesGcmCipher {

    /** @return IV-prefixed ciphertext. */
    fun encrypt(plaintext: String): ByteArray?

    /** @return the plaintext, or `null` if [ciphertext] is corrupt, truncated, or undecryptable. */
    fun decrypt(ciphertext: ByteArray): String?
}
