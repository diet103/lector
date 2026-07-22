package io.github.diet103.lector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.model.TtsFormat
import io.github.diet103.lector.model.TtsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsRepositoryTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun repo() = SettingsRepository(prefs)

    @Test
    fun `defaults are the fast-start ones`() {
        val settings = repo()

        assertNull(settings.voiceId.value)
        assertEquals(TtsModel.FLASH, settings.model.value)
        assertEquals(TtsFormat.STANDARD, settings.format.value)
        assertEquals(1.0f, settings.speed.value, 0.001f)
        assertEquals(SettingsRepository.DEFAULT_MAX_CHARS, settings.maxChars.value)
    }

    @Test
    fun `choices survive a restart`() {
        repo().apply {
            setVoiceId("v-123")
            setModel(TtsModel.TURBO)
            setFormat(TtsFormat.FAST_START)
            setSpeed(1.5f)
            setMaxChars(8_000)
        }

        val reopened = repo()

        assertEquals("v-123", reopened.voiceId.value)
        assertEquals(TtsModel.TURBO, reopened.model.value)
        assertEquals(TtsFormat.FAST_START, reopened.format.value)
        assertEquals(1.5f, reopened.speed.value, 0.001f)
        assertEquals(8_000, reopened.maxChars.value)
    }

    @Test
    fun `speed is held inside a sane range`() {
        val settings = repo()

        settings.setSpeed(9f)
        assertEquals(SettingsRepository.MAX_SPEED, settings.speed.value, 0.001f)

        settings.setSpeed(0.01f)
        assertEquals(SettingsRepository.MIN_SPEED, settings.speed.value, 0.001f)
    }

    @Test
    fun `the cap cannot exceed the chosen model's real limit`() {
        val settings = repo()
        settings.setModel(TtsModel.MULTILINGUAL) // 10k

        settings.setMaxChars(40_000)

        assertEquals(TtsModel.MULTILINGUAL.maxChars, settings.maxChars.value)
    }

    /**
     * The regression that matters: a 40k cap set under Flash must not survive a switch to
     * Multilingual, or every read would fail at the API and look like a Lector bug.
     */
    @Test
    fun `switching to a smaller model pulls an over-large cap down with it`() {
        val settings = repo()
        settings.setModel(TtsModel.FLASH)
        settings.setMaxChars(40_000)

        settings.setModel(TtsModel.MULTILINGUAL)

        assertEquals(10_000, settings.maxChars.value)
        assertEquals(10_000, repo().maxChars.value)
    }

    @Test
    fun `a cap below the floor is raised to it`() {
        val settings = repo()

        settings.setMaxChars(1)

        assertEquals(SettingsRepository.MIN_MAX_CHARS, settings.maxChars.value)
    }

    @Test
    fun `an unknown stored model id falls back to the default rather than crashing`() {
        prefs.edit().putString("model_id", "eleven_removed_v9").commit()

        assertEquals(TtsModel.DEFAULT, repo().model.value)
    }

    @Test
    fun `signing out resets everything`() {
        val settings = repo()
        settings.setVoiceId("v-123")
        settings.setModel(TtsModel.MULTILINGUAL)
        settings.setSpeed(1.75f)

        settings.clear()

        assertNull(settings.voiceId.value)
        assertEquals(TtsModel.DEFAULT, settings.model.value)
        assertEquals(1.0f, settings.speed.value, 0.001f)
        assertNull(repo().voiceId.value)
    }
}
