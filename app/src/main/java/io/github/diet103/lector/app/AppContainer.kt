package io.github.diet103.lector.app

import android.content.Context
import androidx.media3.datasource.cache.Cache
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.data.ApiKeyStore
import io.github.diet103.lector.data.KeystoreAesGcmCipher
import io.github.diet103.lector.data.LastErrorRepository
import io.github.diet103.lector.data.SettingsRepository
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.ocr.ScreenTextRecognizer
import io.github.diet103.lector.playback.TtsCache
import io.github.diet103.lector.tts.ElevenLabsApi
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

    val cache: Cache get() = TtsCache.get(appContext)

    /**
     * Lazy so the bundled ML Kit model is only loaded in a process that actually gets a
     * screenshot shared to it — the text paths never pay for it.
     */
    val screenTextRecognizer: ScreenTextRecognizer by lazy { ScreenTextRecognizer() }
}
