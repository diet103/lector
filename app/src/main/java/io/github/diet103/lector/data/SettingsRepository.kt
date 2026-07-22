package io.github.diet103.lector.data

import android.content.SharedPreferences
import io.github.diet103.lector.model.TtsFormat
import io.github.diet103.lector.model.TtsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User choices that aren't secret (PLAN §5, §6 P6). Kept in a separate prefs file from
 * [ApiKeyStore] so settings can ride along in a backup while the encrypted key deliberately
 * cannot.
 *
 * Two invariants worth knowing:
 *  - **Speed is not stored in the cache key** because it's applied by the player, not the API.
 *    Changing speed must never re-synthesize, or a slider drag would bill the user repeatedly.
 *  - **The cap is clamped to the chosen model's real limit.** Selecting Multilingual (10k) while
 *    a 40k cap is set would otherwise fail at the API and read as a Lector bug.
 */
class SettingsRepository(private val prefs: SharedPreferences) {

    private val _voiceId = MutableStateFlow(prefs.getString(KEY_VOICE_ID, null))
    val voiceId: StateFlow<String?> = _voiceId.asStateFlow()

    private val _model = MutableStateFlow(TtsModel.fromId(prefs.getString(KEY_MODEL_ID, null)))
    val model: StateFlow<TtsModel> = _model.asStateFlow()

    private val _format = MutableStateFlow(TtsFormat.fromId(prefs.getString(KEY_FORMAT_ID, null)))
    val format: StateFlow<TtsFormat> = _format.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, DEFAULT_SPEED))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _maxChars = MutableStateFlow(
        clampCap(prefs.getInt(KEY_MAX_CHARS, DEFAULT_MAX_CHARS), _model.value)
    )
    val maxChars: StateFlow<Int> = _maxChars.asStateFlow()

    /**
     * Offer to delete a shared screenshot once it's been read. Off by default: deleting someone's
     * photos is not a thing to opt them into. Android shows its own confirmation regardless.
     */
    private val _deleteScreenshotAfterReading =
        MutableStateFlow(prefs.getBoolean(KEY_DELETE_SCREENSHOT, false))
    val deleteScreenshotAfterReading: StateFlow<Boolean> =
        _deleteScreenshotAfterReading.asStateFlow()

    fun setVoiceId(voiceId: String) {
        prefs.edit().putString(KEY_VOICE_ID, voiceId).apply()
        _voiceId.value = voiceId
    }

    /** Also re-clamps the cap: the new model may allow far less text than the old one. */
    fun setModel(model: TtsModel) {
        prefs.edit().putString(KEY_MODEL_ID, model.id).apply()
        _model.value = model
        setMaxChars(_maxChars.value)
    }

    fun setFormat(format: TtsFormat) {
        prefs.edit().putString(KEY_FORMAT_ID, format.id).apply()
        _format.value = format
    }

    fun setSpeed(speed: Float) {
        val bounded = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat(KEY_SPEED, bounded).apply()
        _speed.value = bounded
    }

    fun setMaxChars(maxChars: Int) {
        val bounded = clampCap(maxChars, _model.value)
        prefs.edit().putInt(KEY_MAX_CHARS, bounded).apply()
        _maxChars.value = bounded
    }

    fun setDeleteScreenshotAfterReading(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DELETE_SCREENSHOT, enabled).apply()
        _deleteScreenshotAfterReading.value = enabled
    }

    fun clear() {
        prefs.edit().clear().apply()
        _voiceId.value = null
        _model.value = TtsModel.DEFAULT
        _format.value = TtsFormat.DEFAULT
        _speed.value = DEFAULT_SPEED
        _maxChars.value = DEFAULT_MAX_CHARS
        _deleteScreenshotAfterReading.value = false
    }

    private fun clampCap(requested: Int, model: TtsModel): Int =
        requested.coerceIn(MIN_MAX_CHARS, model.maxChars)

    companion object {
        const val PREFS_NAME = "lector_settings"

        const val DEFAULT_MAX_CHARS = 5_000
        const val MIN_MAX_CHARS = 500

        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_FORMAT_ID = "format_id"
        private const val KEY_SPEED = "speed"
        private const val KEY_MAX_CHARS = "max_chars"
        private const val KEY_DELETE_SCREENSHOT = "delete_screenshot_after_reading"
    }
}
