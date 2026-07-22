package io.github.diet103.lector.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.tts.CacheKeys
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PLAN §8's Robolectric playback integration suite — the permanent regression fence for the
 * billing invariant (risk register #1/#2). MockWebServer stands in for ElevenLabs, serving
 * chunked MP3 through the real production chain assembled by [TtsPlayerFactory];
 * `server.requestCount` is the ground truth for what a Media3 upgrade would have cost in
 * credits. Playback runs on an auto-advancing [FakeClock], so a second of audio costs
 * milliseconds of test time.
 */
// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TtsStreamingBillingTest {

    @get:Rule
    val mediaCodecConfig: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val audio = TestMp3.bytes()
    private val request = SpeakRequest(text = "One utterance, one POST.", voiceId = "voice123")

    /** 40 frames x 1152 samples per frame / 44100 Hz, in milliseconds. */
    private val EXPECTED_DURATION_MS = 40 * 1152 * 1000.0 / 44100.0

    private lateinit var server: MockWebServer
    private lateinit var cache: SimpleCache
    private lateinit var registry: SpeakRequestRegistry
    private lateinit var player: ExoPlayer
    private var apiKey = "test-api-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        cache = SimpleCache(
            tempFolder.newFolder("tts"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context)
        )
        registry = SpeakRequestRegistry()
        player = TtsPlayerFactory.create(
            context = context,
            registry = registry,
            cache = cache,
            okHttpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = FakeClock(true)
        ) { apiKey }
    }

    @After
    fun tearDown() {
        player.release()
        cache.release()
        server.shutdown()
    }

    // ① one-POST invariant with correct method/headers/body/query
    @Test
    fun `one utterance issues exactly one POST, shaped like the ElevenLabs contract`() {
        server.enqueue(mp3Response())

        speak(request)
        runUntilEnded()

        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/text-to-speech/voice123/stream?output_format=mp3_44100_128", recorded.path)
        assertEquals(apiKey, recorded.getHeader("xi-api-key"))
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("audio/mpeg", recorded.getHeader("Accept"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(request.text, body.getString("text"))
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))
        // Every body field is billable surface — adding one must be a deliberate, reviewed change.
        assertEquals(2, body.length())
    }

    // ② pause→resume→end: still one request, and the cache contains the key (flag-strip canary)
    @Test
    fun `pause and resume never re-POST, and the full response lands in the cache`() {
        server.enqueue(mp3Response().throttleBody(4096, 100, TimeUnit.MILLISECONDS))

        player.setMediaItem(MediaItem.fromUri(registry.register(request)))
        player.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
        player.play()
        player.pause()
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
        player.play()
        runUntilEnded()

        assertEquals(1, server.requestCount)
        val key = CacheKeys.forRequest(request)
        assertTrue(cache.keys.contains(key))
        // The canary for FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN: if the resolver ever stops
        // stripping it, the chunked (unknown-length) response silently bypasses the cache.
        assertTrue(cache.isCached(key, 0, audio.size.toLong()))
    }

    // ③ replay = cache hit, zero requests
    @Test
    fun `replaying the same text is a cache hit with zero new requests`() {
        server.enqueue(mp3Response())
        server.enqueue(MockResponse().setResponseCode(500).setBody("a second POST would re-bill"))

        speak(request)
        runUntilEnded()
        assertEquals(1, server.requestCount)

        speak(request)
        runUntilEnded()

        assertEquals(1, server.requestCount)
    }

    // ④ mid-body disconnect → loud error, no retry
    @Test
    fun `mid-body disconnect surfaces a loud error and is never retried`() {
        repeat(3) {
            server.enqueue(mp3Response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        }

        speak(request)
        val error = TestPlayerRunHelper.runUntilError(player)

        assertEquals(1, server.requestCount)
        assertTrue(causeChain(error).any { it is IOException })
    }

    // ⑤ 401 body → surfaced for the error mapper (P5 tightens this to TtsError.InvalidKey)
    @Test
    fun `401 surfaces response code and body without retry, and caches nothing`() {
        apiKey = "scoped-key-without-tts-permission"
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail":{"status":"missing_permissions","message":"key is missing the Text to Speech permission"}}""")
            )
        }

        speak(request)
        val error = TestPlayerRunHelper.runUntilError(player)

        assertEquals(1, server.requestCount)
        val http = causeChain(error)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .first()
        assertEquals(401, http.responseCode)
        assertTrue(String(http.responseBody).contains("missing_permissions"))
        assertFalse(cache.keys.contains(CacheKeys.forRequest(request)))
    }

    // ⑥ replace mid-stream: one POST per utterance, the replacement plays cleanly
    @Test
    fun `replacing playback mid-stream costs exactly one POST for the new utterance`() {
        val longAudio = TestMp3.bytes(frames = 120)
        server.enqueue(mp3Response(longAudio).throttleBody(8192, 300, TimeUnit.MILLISECONDS))
        val first = SpeakRequest(text = "A long first utterance about to be replaced.", voiceId = "voiceA")
        speak(first)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)

        server.enqueue(mp3Response())
        val second = SpeakRequest(text = "The replacement.", voiceId = "voiceB")
        speak(second)
        runUntilEnded()

        assertEquals(2, server.requestCount)
        assertNull(player.playerError)
        assertTrue(cache.isCached(CacheKeys.forRequest(second), 0, audio.size.toLong()))
    }

    // ⑦ delayed body → buffering state exposed before audio starts
    @Test
    fun `slow time-to-first-byte is exposed as buffering before audio starts`() {
        val states = mutableListOf<Int>()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states.add(playbackState)
            }
        })
        server.enqueue(mp3Response().setHeadersDelay(600, TimeUnit.MILLISECONDS))

        speak(request)
        runUntilEnded()

        assertEquals(Player.STATE_BUFFERING, states.first())
        assertTrue(states.contains(Player.STATE_READY))
        assertEquals(Player.STATE_ENDED, states.last())
    }

    // ⑧ v0.2 reader: a fully cached read reports an *accurate* duration. Asserting merely
    // "not TIME_UNSET" would pass on a garbage value, and the reader maps character offsets onto
    // this number — a wrong duration means every highlight and every tap-to-seek is wrong.
    @Test
    fun `a fully cached read exposes an accurate duration`() {
        server.enqueue(mp3Response())
        speak(request)
        runUntilEnded()

        speak(request)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)

        assertNotEquals(
            "duration is TIME_UNSET on a cached CBR MP3 — the reader cannot map words to time",
            C.TIME_UNSET,
            player.duration
        )
        // 40 frames x 1152 samples / 44100 Hz = 1044.9 ms. Media3 derives this from the stream
        // length and the constant bitrate; a few ms of slack covers frame-boundary rounding.
        assertEquals(EXPECTED_DURATION_MS, player.duration.toDouble(), 30.0)
    }

    // ⑨ v0.2 reader: seeking within a fully cached read is served from disk. This is the whole
    // basis for tap-a-word-to-seek: seeks are stripped everywhere else because ElevenLabs ignores
    // Range and would re-bill, but cached bytes cost nothing. Asserts the seek actually *landed*,
    // not just that nothing exploded — a no-op seek would pass a request-count-only check.
    //
    // Note what this suite deliberately does NOT assert: how `duration` or
    // `isCurrentMediaItemSeekable` behave *mid-stream*. The auto-advancing FakeClock races through
    // `throttleBody`, so the body is always fully cached before STATE_READY and there is no
    // observable in-flight state here. Seeking is therefore granted on cache state alone — never
    // on the player's own seekability, which was never verified for a partial read.
    @Test
    fun `seeking inside a fully cached read lands, and issues no new request`() {
        server.enqueue(mp3Response())
        server.enqueue(MockResponse().setResponseCode(500).setBody("a seek must never re-POST"))

        speak(request)
        runUntilEnded()
        assertEquals(1, server.requestCount)

        speak(request)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
        val target = player.duration / 2
        player.seekTo(target)
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)

        assertEquals(target.toDouble(), player.currentPosition.toDouble(), 60.0)
        runUntilEnded()

        assertNull(player.playerError)
        assertEquals(1, server.requestCount)
    }


    // ⑪ v0.2 reader: does Media3 record the content length of a chunked, unknown-length response
    // once it has been fully read? If it does, "is this read entirely on disk?" can be answered
    // from the cache itself rather than from bookkeeping of our own that may never have run.
    @Test
    fun `a completed read records its content length in the cache`() {
        server.enqueue(mp3Response())

        speak(request)
        runUntilEnded()

        val key = CacheKeys.forRequest(request)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        assertEquals(audio.size.toLong(), length)
        assertEquals(audio.size.toLong(), cache.getCachedBytes(key, 0, length))
    }

    // And the negative: a read still arriving must NOT look complete, or the reader would offer
    // seeking on something that would re-bill.
    @Test
    fun `an unread key has no recorded content length`() {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata("never-fetched"))
        assertEquals(C.LENGTH_UNSET.toLong(), length)
    }

    private fun mp3Response(body: ByteArray = audio): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "audio/mpeg")
            .setChunkedBody(Buffer().write(body), 2048)

    private fun speak(request: SpeakRequest) {
        player.setMediaItem(MediaItem.fromUri(registry.register(request)))
        player.prepare()
        player.play()
    }

    private fun runUntilEnded() {
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
    }

    private fun causeChain(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause }
}
