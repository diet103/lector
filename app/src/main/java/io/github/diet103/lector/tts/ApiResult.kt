package io.github.diet103.lector.tts

import io.github.diet103.lector.model.TtsError

/**
 * Success or a mapped [TtsError] — deliberately not `kotlin.Result`, which can only carry a
 * `Throwable` and would push callers back to string-matching exception messages.
 */
sealed interface ApiResult<out T> {

    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failed(val error: TtsError) : ApiResult<Nothing>
}
