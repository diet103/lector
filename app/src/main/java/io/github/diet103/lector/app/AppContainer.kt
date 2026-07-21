package io.github.diet103.lector.app

import android.content.Context
import androidx.media3.datasource.cache.Cache
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.ocr.ScreenTextRecognizer
import io.github.diet103.lector.playback.TtsCache
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual DI root (PLAN §5). Holds the singletons the UI and [PlaybackService][io.github.diet103.lector.playback.PlaybackService]
 * must share within the process: the registry that maps a `lector://tts/<key>` back to its
 * [SpeakRequest][io.github.diet103.lector.model.SpeakRequest], the one OkHttp client, and the
 * one audio cache. Grows through later phases (ApiKeyStore, SettingsRepository, ElevenLabsApi,
 * … per §5); today it carries only what P2 needs.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val registry = SpeakRequestRegistry()

    /** Editable in the dev spike; replaced by encrypted [ApiKeyStore] in P5. */
    @Volatile
    var apiKey: String = BuildConfig.DEV_ELEVEN_KEY

    val apiKeyProvider: () -> String = { apiKey }

    /** The dev voice until P6's picker lands; ReadAloudActivity speaks with it. */
    val defaultVoiceId: String = BuildConfig.DEV_VOICE_ID

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
                    upstreamPostCount.incrementAndGet()
                    chain.proceed(chain.request())
                }
            }
        }
        .build()

    val cache: Cache get() = TtsCache.get(appContext)

    /**
     * Lazy so the bundled ML Kit model is only loaded in a process that actually gets a
     * screenshot shared to it — the text paths never pay for it.
     */
    val screenTextRecognizer: ScreenTextRecognizer by lazy { ScreenTextRecognizer() }
}
