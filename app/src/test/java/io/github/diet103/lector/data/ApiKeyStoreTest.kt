package io.github.diet103.lector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ApiKeyStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ApiKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `a saved key round-trips`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher())

        assertTrue(store.save("sk_abc123"))

        assertEquals("sk_abc123", ApiKeyStore(prefs, FakeAesGcmCipher()).read())
    }

    @Test
    fun `nothing stored means no key`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher())

        assertNull(store.read())
        assertFalse(store.hasKey())
    }

    @Test
    fun `the key is not stored in plaintext`() {
        ApiKeyStore(prefs, FakeAesGcmCipher()).save("sk_secret_value")

        val raw = prefs.all.values.joinToString()
        assertFalse("the raw prefs contained the key", raw.contains("sk_secret_value"))
    }

    @Test
    fun `surrounding whitespace is trimmed off a pasted key`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher())

        store.save("  sk_pasted\n")

        assertEquals("sk_pasted", store.read())
    }

    @Test
    fun `a blank key is refused and nothing is stored`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher())

        assertFalse(store.save("   "))
        assertNull(store.read())
    }

    @Test
    fun `an encryption failure reports failure and stores nothing`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher(failEncrypt = true))

        assertFalse(store.save("sk_abc123"))
        assertNull(store.read())
    }

    @Test
    fun `undecryptable ciphertext reads as no key and clears itself`() {
        ApiKeyStore(prefs, FakeAesGcmCipher()).save("sk_abc123")

        // A Keystore key invalidated by a lock-screen change, or a blob restored from elsewhere.
        val store = ApiKeyStore(prefs, FakeAesGcmCipher(failDecrypt = true))

        assertNull(store.read())
        assertTrue("the undecryptable blob should have been dropped", prefs.all.isEmpty())
    }

    @Test
    fun `clear removes the key`() {
        val store = ApiKeyStore(prefs, FakeAesGcmCipher())
        store.save("sk_abc123")

        store.clear()

        assertNull(store.read())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `repeated reads decrypt only once`() {
        ApiKeyStore(prefs, FakeAesGcmCipher()).save("sk_abc123")
        val cipher = FakeAesGcmCipher()
        val store = ApiKeyStore(prefs, cipher)

        repeat(5) { store.read() }

        assertEquals(1, cipher.decryptCalls)
    }

    @Test
    fun `a save is visible to later reads without decrypting again`() {
        val cipher = FakeAesGcmCipher()
        val store = ApiKeyStore(prefs, cipher)

        store.save("sk_fresh")

        assertEquals("sk_fresh", store.read())
        assertEquals(0, cipher.decryptCalls)
    }
}
