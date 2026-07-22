package io.github.diet103.lector.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.data.ApiKeyStore
import io.github.diet103.lector.data.KeystoreAesGcmCipher
import io.github.diet103.lector.data.LastErrorRepository
import io.github.diet103.lector.data.ReadContext
import io.github.diet103.lector.data.SettingsRepository
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.HistoryStore
import io.github.diet103.lector.history.ReadSource
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.ocr.ScreenTextRecognizer
import io.github.diet103.lector.playback.TtsCache
import io.github.diet103.lector.tts.ElevenLabsApi
import io.github.diet103.lector.web.ArticleFetcher
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual DI root (PLAN §5). Holds the singletons the UI and [PlaybackService][io.github.diet103.lector.playback.PlaybackService]
 * must share within the process: the registry that maps a `lector://tts/<key>` back to its
 * [SpeakRequest][io.github.diet103.lector.model.SpeakRequest], the one OkHttp client, the one
 * audio cache, the encrypted key store, and the error ledger.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val registry = SpeakRequestRegistry()

    val apiKeyStore = ApiKeyStore(
        prefs = appContext.getSharedPreferences(ApiKeyStore.PREFS_NAME, Context.MODE_PRIVATE),
        cipher = KeystoreAesGcmCipher()
    )

    val settings = SettingsRepository(
        appContext.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
    )

    val lastError = LastErrorRepository()

    /**
     * Lazy: a share that only ever speaks and finishes shouldn't pay to open a database, and the
     * playback service process touches it only when a read actually starts.
     */
    val history: HistoryStore by lazy { HistoryStore(appContext) }

    /**
     * The encrypted store is the only source of truth — no BuildConfig fallback, so onboarding is
     * exercised on debug builds exactly as a real user meets it.
     */
    val apiKeyProvider: () -> String = { apiKeyStore.read().orEmpty() }

    /** Chosen during onboarding (first voice the account can actually use); P6 adds the picker. */
    val currentVoiceId: String? get() = settings.voiceId.value

    /** True once there's both a key and a voice — anything less can't speak. */
    val isSetUp: Boolean get() = apiKeyStore.hasKey() && !currentVoiceId.isNullOrBlank()

    /**
     * Text someone tried to read before finishing setup. Held so a selection isn't silently lost
     * to the onboarding detour — Home picks it up and drops it (PLAN §6 P5).
     */
    @Volatile
    var pendingText: String? = null

    /**
     * One place every entry point builds its request, so the selection toolbar, the share sheet,
     * the screenshot path and the try-it box can't drift apart on voice, model or format.
     * Speed is deliberately absent: the player applies it, so changing it never re-bills.
     */
    fun speakRequest(text: String) = SpeakRequest(
        text = text,
        voiceId = currentVoiceId.orEmpty(),
        modelId = settings.model.value.id,
        outputFormat = settings.format.value.id
    )

    /**
     * Starts a read: builds the request, remembers where it came from, and returns the
     * `lector://tts/<key>` URI to hand the player. Every entry point goes through here, which is
     * what keeps the history honest about its own sources.
     *
     * Nothing is written to disk yet — [PlaybackService][io.github.diet103.lector.playback.PlaybackService]
     * records the row only once audio actually starts, so a read that dies on a bad key never
     * shows up as something you read.
     */
    fun beginRead(
        text: String,
        source: ReadSource,
        title: String? = null,
        sourceUrl: String? = null
    ): Uri = registry.register(
        speakRequest(text),
        ReadContext(source = source, title = title, sourceUrl = sourceUrl)
    )

    /**
     * Re-registers a stored read so it can be replayed. The registry is memory-only, so without
     * this a cached read becomes unplayable the moment the process dies — history is what gives it
     * somewhere to come back from. No [ReadContext]: the row already exists and only its
     * `lastReadAt` moves.
     */
    fun replay(entry: HistoryEntry): Uri = registry.register(entry.toSpeakRequest())

    /**
     * Whether replaying this costs nothing — i.e. every byte is already on disk. Used to label
     * history entries honestly, and to decide whether seeking may be offered at all.
     *
     * Asks the **cache**, not our own history row. An earlier version compared against an
     * `audioBytes` column written only when a read reached its end, which meant a read you didn't
     * listen all the way through was permanently mislabelled as costly and permanently un-seekable
     * — exactly backwards, since not finishing something is the usual reason to open it again.
     * Media3 records the content length once a response has been fully read even though it arrives
     * chunked with no length header (pinned by TtsStreamingBillingTest).
     */
    fun isFullyCached(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && cache.getCachedBytes(key, 0, length) >= length
    }

    fun isFullyCached(entry: HistoryEntry): Boolean = isFullyCached(entry.key)

    /** The active truncation cap (PLAN §2), overridable in settings and bounded by the model. */
    val maxChars: Int get() = settings.maxChars.value

    /**
     * Counts real upstream requests so the dev screen can still show the billing invariant on
     * the live service (the automated suite is the real fence). Debug builds only — the
     * interceptor is never added in release.
     */
    val upstreamPostCount = AtomicInteger(0)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor { chain ->
                    if (chain.request().method == "POST") upstreamPostCount.incrementAndGet()
                    chain.proceed(chain.request())
                }
            }
        }
        .build()

    val elevenLabsApi = ElevenLabsApi(okHttpClient)

    /** Turns a shared link into readable prose so Lector doesn't spell a URL out loud. */
    val articleFetcher = ArticleFetcher(okHttpClient)

    val cache: Cache get() = TtsCache.get(appContext)

    /**
     * Drops every cached MP3. Replays stop being free after this, so it's a user-initiated
     * action only. @return bytes reclaimed.
     */
    fun clearAudioCache(): Long {
        val store = TtsCache.get(appContext)
        val before = store.cacheSpace
        store.keys.toList().forEach { store.removeResource(it) }
        return before - store.cacheSpace
    }

    /**
     * Lazy so the bundled ML Kit model is only loaded in a process that actually gets a
     * screenshot shared to it — the text paths never pay for it.
     */
    val screenTextRecognizer: ScreenTextRecognizer by lazy { ScreenTextRecognizer() }
}
