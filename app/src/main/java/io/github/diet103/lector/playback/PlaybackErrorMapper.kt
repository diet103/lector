package io.github.diet103.lector.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.github.diet103.lector.model.TtsError
import io.github.diet103.lector.tts.ElevenLabsErrorMapper

/**
 * `PlaybackException → TtsError` (PLAN §5).
 *
 * A failed synthesis reaches us as a player error, with the ElevenLabs response buried a few
 * `cause` links down. Without this, an expired key surfaces to the user as
 * "Response code: 401" — technically true and completely useless. Digging the response body out
 * and running it through [ElevenLabsErrorMapper] is what makes the message actionable.
 */
object PlaybackErrorMapper {

    fun map(error: PlaybackException): TtsError {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val body = cause.responseBody.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
                return ElevenLabsErrorMapper.map(cause.responseCode, body)
            }
            cause = cause.cause
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> TtsError.Offline

            else -> TtsError.Unknown(0, error.cause?.message ?: error.message)
        }
    }
}
