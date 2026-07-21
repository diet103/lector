package io.github.diet103.lector.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.model.TtsError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A synthesis failure arrives as a player error with the ElevenLabs body buried a few `cause`
 * links down. Untranslated, a scoped key reads as "Response code: 401" (PLAN §3).
 */
// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaybackErrorMapperTest {

    private fun httpFailure(code: Int, body: String): PlaybackException {
        val invalid = HttpDataSource.InvalidResponseCodeException(
            code,
            "message",
            null,
            emptyMap(),
            DataSpec.Builder().setUri("https://api.elevenlabs.io/v1/text-to-speech/v/stream").build(),
            body.toByteArray(Charsets.UTF_8)
        )
        // Media3 wraps the data-source failure before it reaches a Player.Listener.
        return PlaybackException(
            "Source error",
            IOException(invalid),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
    }

    @Test
    fun `a scoped key buried in the cause chain is recovered`() {
        val error = httpFailure(401, """{"detail":{"status":"missing_permissions"}}""")

        assertEquals(TtsError.MissingPermissions, PlaybackErrorMapper.map(error))
    }

    @Test
    fun `a cloned voice rejection is recovered`() {
        val error = httpFailure(401, """{"detail":{"status":"ivc_not_permitted"}}""")

        assertEquals(TtsError.ClonedVoiceNotAllowed, PlaybackErrorMapper.map(error))
    }

    @Test
    fun `an exhausted quota is recovered`() {
        val error = httpFailure(401, """{"detail":{"status":"quota_exceeded"}}""")

        assertEquals(TtsError.QuotaExceeded, PlaybackErrorMapper.map(error))
    }

    @Test
    fun `an http failure with an empty body still uses its code`() {
        val error = httpFailure(402, "")

        assertEquals(TtsError.PaidPlanRequired, PlaybackErrorMapper.map(error))
    }

    @Test
    fun `losing the network mid-stream reads as offline`() {
        val error = PlaybackException(
            "Unable to connect",
            IOException("network"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        assertEquals(TtsError.Offline, PlaybackErrorMapper.map(error))
    }

    @Test
    fun `an unrecognised player error keeps its detail rather than vanishing`() {
        val error = PlaybackException(
            "Decoder init failed",
            IllegalStateException("no decoder"),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )

        assertEquals(TtsError.Unknown(0, "no decoder"), PlaybackErrorMapper.map(error))
    }
}
