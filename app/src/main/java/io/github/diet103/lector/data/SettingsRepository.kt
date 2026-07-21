package io.github.diet103.lector.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User choices that aren't secret (PLAN §5). Kept in a separate prefs file from [ApiKeyStore] so
 * settings can ride along in a backup while the encrypted key deliberately cannot.
 *
 * P5 needs only the chosen voice; P6's picker, model, speed, format and cap override land here.
 */
class SettingsRepository(private val prefs: SharedPreferences) {

    private val _voiceId = MutableStateFlow(prefs.getString(KEY_VOICE_ID, null))

    val voiceId: StateFlow<String?> = _voiceId.asStateFlow()

    fun setVoiceId(voiceId: String) {
        prefs.edit().putString(KEY_VOICE_ID, voiceId).apply()
        _voiceId.value = voiceId
    }

    fun clear() {
        prefs.edit().clear().apply()
        _voiceId.value = null
    }

    companion object {
        const val PREFS_NAME = "lector_settings"
        private const val KEY_VOICE_ID = "voice_id"
    }
}
