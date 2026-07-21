package io.github.diet103.lector.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.model.TtsError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ElevenLabsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ElevenLabsApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ElevenLabsApi(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Trimmed from a real `/v1/user` response, extra fields kept to prove they're ignored. */
    private val userBody = """
        {"subscription":{"tier":"free","character_count":783,"character_limit":10000,
        "can_use_instant_voice_cloning":false,"voice_limit":3,"character_refresh_period":"monthly_period"},
        "first_name":"Dieter","is_new_user":true,"seat_type":"workspace_admin"}
    """.trimIndent()

    @Test
    fun `the account is parsed and the key is sent as a header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(userBody))

        val result = api.getUser("sk_test_key")

        val account = (result as ApiResult.Ok).value
        assertEquals("free", account.tier)
        assertEquals(783, account.charactersUsed)
        assertEquals(10000, account.characterLimit)
        assertEquals(9217, account.charactersRemaining)
        assertEquals(false, account.canUseInstantVoiceCloning)
        assertEquals("Dieter", account.firstName)

        val recorded = server.takeRequest()
        assertEquals("sk_test_key", recorded.getHeader("xi-api-key"))
        assertEquals("/v1/user", recorded.path)
    }

    @Test
    fun `a rejected key comes back as a typed error, not an exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"status":"missing_permissions","message":"nope"}}""")
        )

        val result = api.getUser("sk_scoped")

        assertEquals(TtsError.MissingPermissions, (result as ApiResult.Failed).error)
    }

    @Test
    fun `voices are parsed and unusable entries skipped`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"voices":[
                  {"voice_id":"v1","name":"George","category":"premade","preview_url":"https://x/p.mp3"},
                  {"name":"broken entry with no id","category":"premade"},
                  {"voice_id":"v2","name":"Dieter","category":"cloned"}
                ]}
                """.trimIndent()
            )
        )

        val voices = (api.getVoices("sk_test_key") as ApiResult.Ok).value

        assertEquals(2, voices.size)
        assertEquals("George", voices[0].name)
        assertEquals("https://x/p.mp3", voices[0].previewUrl)
        assertTrue("a cloned voice must be flagged", voices[1].isCloned)
        assertEquals(null, voices[1].previewUrl)
    }

    @Test
    fun `a body that is not json fails cleanly instead of crashing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>maintenance</html>"))

        val result = api.getUser("sk_test_key")

        assertTrue(result is ApiResult.Failed)
    }

    @Test
    fun `an unreachable server reads as offline`() = runBlocking {
        server.shutdown()

        val result = api.getUser("sk_test_key")

        assertEquals(TtsError.Offline, (result as ApiResult.Failed).error)
    }
}
