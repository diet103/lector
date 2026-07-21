package io.github.diet103.lector.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.model.TtsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/**
 * Table-driven over (HTTP code × detail), including unknowns (PLAN §8). The first three bodies
 * are verbatim from live responses — the same HTTP 401 means three different things.
 */
// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ElevenLabsErrorMapperTest {

    @Test
    fun `observed - a cloned voice on the free tier`() {
        val body = """
            {"detail":{"type":"authorization_error","code":"subscription_required",
            "message":"Instantly cloned voices are not available on your current plan.",
            "status":"ivc_not_permitted","request_id":"16d26e0e"}}
        """.trimIndent()

        assertEquals(TtsError.ClonedVoiceNotAllowed, ElevenLabsErrorMapper.map(401, body))
    }

    @Test
    fun `observed - a scope-restricted key`() {
        val body = """{"detail":{"status":"missing_permissions","message":"The API key is missing permissions."}}"""

        assertEquals(TtsError.MissingPermissions, ElevenLabsErrorMapper.map(401, body))
    }

    @Test
    fun `observed - a library voice on the free tier`() {
        val body = """{"detail":{"status":"paid_plan_required","message":"This voice requires a paid plan."}}"""

        assertEquals(TtsError.PaidPlanRequired, ElevenLabsErrorMapper.map(402, body))
    }

    @Test
    fun `an exhausted quota is not a key problem`() {
        val body = """{"detail":{"status":"quota_exceeded","message":"Not enough characters."}}"""

        val error = ElevenLabsErrorMapper.map(401, body)

        assertEquals(TtsError.QuotaExceeded, error)
        assertTrue("quota is not fixed by changing the key", !error.isKeyProblem)
    }

    @Test
    fun `status wins over the http code`() {
        // A 500 that still names a known status must not be reported as a server outage.
        val body = """{"detail":{"status":"voice_not_found"}}"""

        assertEquals(TtsError.VoiceNotFound, ElevenLabsErrorMapper.map(500, body))
    }

    @Test
    fun `an unrecognised status falls back to the http code`() {
        val body = """{"detail":{"status":"some_future_status","message":"who knows"}}"""

        assertEquals(TtsError.RateLimited, ElevenLabsErrorMapper.map(429, body))
    }

    @Test
    fun `a bare 401 with no body reads as a bad key`() {
        assertEquals(TtsError.InvalidKey, ElevenLabsErrorMapper.map(401, null))
    }

    @Test
    fun `server errors keep their code`() {
        assertEquals(TtsError.ServerProblem(503), ElevenLabsErrorMapper.map(503, ""))
    }

    @Test
    fun `a string detail is carried through rather than discarded`() {
        val error = ElevenLabsErrorMapper.map(418, """{"detail":"I am a teapot"}""")

        assertEquals(TtsError.Unknown(418, "I am a teapot"), error)
        assertTrue(error.message.contains("I am a teapot"))
        assertTrue(error.message.contains("418"))
    }

    @Test
    fun `a validation array detail surfaces its first message`() {
        val body = """{"detail":[{"loc":["body","text"],"msg":"field required","type":"value_error"}]}"""

        assertEquals(TtsError.Unknown(422, "field required"), ElevenLabsErrorMapper.map(422, body))
    }

    @Test
    fun `a malformed body never throws`() {
        assertEquals(TtsError.Unknown(400, null), ElevenLabsErrorMapper.map(400, "<html>nope</html>"))
    }

    @Test
    fun `connectivity failures map to offline`() {
        assertEquals(TtsError.Offline, ElevenLabsErrorMapper.mapFailure(UnknownHostException("no dns")))
        assertEquals(TtsError.Offline, ElevenLabsErrorMapper.mapFailure(IOException("socket closed")))
    }

    @Test
    fun `a non-io failure keeps its message`() {
        val error = ElevenLabsErrorMapper.mapFailure(IllegalStateException("boom"))

        assertEquals(TtsError.Unknown(0, "boom"), error)
    }
}
