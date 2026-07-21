package io.github.diet103.lector.data

import io.github.diet103.lector.model.TtsError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The error ledger (PLAN §5).
 *
 * Failures happen inside the playback service, but the person who needs to read about them is
 * looking at a scrim over another app, or at the home screen later. This holds the most recent
 * one so whichever surface appears next can explain what went wrong instead of showing nothing.
 * Process-scoped and deliberately not persisted — a stale error from three days ago is noise.
 */
class LastErrorRepository {

    private val _lastError = MutableStateFlow<TtsError?>(null)

    val lastError: StateFlow<TtsError?> = _lastError.asStateFlow()

    fun record(error: TtsError) {
        _lastError.value = error
    }

    fun clear() {
        _lastError.value = null
    }
}
