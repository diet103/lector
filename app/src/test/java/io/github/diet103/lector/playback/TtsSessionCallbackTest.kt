package io.github.diet103.lector.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.model.SpeakRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TtsSessionCallbackTest {

    private val registry = SpeakRequestRegistry()
    private val callback = TtsSessionCallback(registry)

    private val allSeekCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD
    )

    // No scrubber anywhere (notification/QS/Assistant) → no seek can re-open the stream at an
    // offset and re-bill. Billing defense #2 (PLAN §4).
    @Test
    fun `no seek command is ever offered to a controller`() {
        val commands = callback.availablePlayerCommands
        for (command in allSeekCommands) {
            assertFalse("seek command $command must be stripped", commands.contains(command))
        }
    }

    @Test
    fun `play pause and stop remain available`() {
        val commands = callback.availablePlayerCommands
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_STOP))
        assertTrue(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
    }

    @Test
    fun `a registered media id resolves to its lector uri`() {
        val request = SpeakRequest(text = "resolve me", voiceId = "voiceX")
        val key = registry.register(request).lastPathSegment!!

        val resolved = callback.resolve(listOf(MediaItem.Builder().setMediaId(key).build()))

        assertEquals(registry.uriForKey(key), resolved.single().localConfiguration?.uri)
    }

    @Test
    fun `an unknown media id passes through without a playable uri`() {
        val resolved = callback.resolve(listOf(MediaItem.Builder().setMediaId("not-registered").build()))

        assertNull(resolved.single().localConfiguration)
    }
}
