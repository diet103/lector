package io.github.diet103.lector.data

import android.content.SharedPreferences
import android.util.Base64

/**
 * The user's ElevenLabs key at rest (PLAN §5): encrypted by [AesGcmCipher], Base64'd into
 * SharedPreferences, and excluded from backup by the rule files in `res/xml`.
 *
 * Reads are served from an in-memory copy because the playback resolver asks for the key on every
 * request and a Keystore round-trip per stream would be wasteful.
 *
 * **Undecryptable means signed out, not broken.** A key invalidated by a lock-screen change, or a
 * blob restored onto a different device, cannot be recovered — so the stored value is dropped and
 * the user is treated as having no key. Silently re-prompting beats crash-looping on every launch.
 */
class ApiKeyStore(
    private val prefs: SharedPreferences,
    private val cipher: AesGcmCipher
) {

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    fun read(): String? {
        if (loaded) return cached

        synchronized(this) {
            if (loaded) return cached

            val stored = prefs.getString(KEY_CIPHERTEXT, null)
            cached = stored
                ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?.let { cipher.decrypt(it) }
                ?.takeIf { it.isNotBlank() }

            if (stored != null && cached == null) clearStorage()
            loaded = true
            return cached
        }
    }

    /** @return `false` if the key could not be encrypted, in which case nothing was stored. */
    fun save(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return false

        val encrypted = cipher.encrypt(trimmed) ?: return false
        synchronized(this) {
            prefs.edit().putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
            cached = trimmed
            loaded = true
        }
        return true
    }

    fun clear() {
        synchronized(this) {
            clearStorage()
            cached = null
            loaded = true
        }
    }

    fun hasKey(): Boolean = !read().isNullOrBlank()

    private fun clearStorage() {
        prefs.edit().remove(KEY_CIPHERTEXT).commit()
    }

    companion object {
        const val PREFS_NAME = "lector_credentials"
        private const val KEY_CIPHERTEXT = "eleven_api_key"
    }
}
