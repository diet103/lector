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
    //
    // Unconditional, and it must stay that way. A version of this handed the seek commands out
    // whenever the current read happened to be fully cached, so the guarantee held only some of
    // the time and the reader could not tell when. The reader asks with ReadFromCommand instead.
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

    // Anything already on disk is pointed at the replay-only URI, which GuardedUpstreamDataSource
    // refuses to fetch. So a read the user was told is free cannot quietly become a purchase if the
    // cache is evicted between the badge being drawn and playback starting.
    @Test
    fun `a fully cached media id resolves to the replay-only uri`() {
        val request = SpeakRequest(text = "already on disk", voiceId = "voiceX")
        val key = registry.register(request).lastPathSegment!!
        val cachedCallback = TtsSessionCallback(registry, isFullyCached = { it == key })

        val resolved = cachedCallback.resolve(listOf(MediaItem.Builder().setMediaId(key).build()))

        assertEquals(registry.cachedUriForKey(key), resolved.single().localConfiguration?.uri)
    }

    @Test
    fun `an unknown media id passes through without a playable uri`() {
        val resolved = callback.resolve(listOf(MediaItem.Builder().setMediaId("not-registered").build()))

        assertNull(resolved.single().localConfiguration)
    }

    // Only Lector's own screens may move the playhead. Everything else — Assistant, a car head
    // unit, Bluetooth — can play and pause and nothing more.
    @Test
    fun `our own ui may ask to read from a point`() {
        val commands = callback.sessionCommandsFor(isOwnPackage = true, isMediaNotification = false)

        assertTrue(commands.contains(ReadFromCommand.COMMAND))
    }

    @Test
    fun `another app may not ask to read from a point`() {
        val commands = callback.sessionCommandsFor(isOwnPackage = false, isMediaNotification = false)

        assertFalse(commands.contains(ReadFromCommand.COMMAND))
    }

    // The shade's controls stay the same whatever is playing, rather than growing a way to scrub
    // that sometimes costs money and sometimes doesn't.
    @Test
    fun `the media notification may not ask to read from a point`() {
        val commands = callback.sessionCommandsFor(isOwnPackage = true, isMediaNotification = true)

        assertFalse(commands.contains(ReadFromCommand.COMMAND))
    }
}
