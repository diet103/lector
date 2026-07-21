package io.github.diet103.lector.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide singleton — SimpleCache throws if two instances open the same directory.
 * Lives in cacheDir so the OS may purge it; this is replay/scrub protection, not a library.
 */
object TtsCache {

    private const val MAX_BYTES = 50L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.cacheDir, "tts"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { instance = it }
        }
}
