package io.github.diet103.lector.tts

import io.github.diet103.lector.model.TtsError
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * `(HTTP code, error body) → TtsError` (PLAN §3).
 *
 * ElevenLabs puts the useful discriminator in `detail.status`, and the same HTTP code covers very
 * different problems — 401 alone is any of a bad key, a scoped key, an exhausted quota, or a
 * cloned voice on the wrong plan. So status is consulted first and the HTTP code is only the
 * fallback. `detail` is not always an object either: it can be a bare string, or FastAPI's
 * validation array. All three shapes are handled, and anything unrecognised keeps its code and
 * text rather than being flattened into a useless "something went wrong".
 */
object ElevenLabsErrorMapper {

    fun map(httpCode: Int, body: String?): TtsError {
        val detail = parseDetail(body)

        byStatus(detail.status ?: detail.code)?.let { return it }

        return when {
            httpCode == 401 -> TtsError.InvalidKey
            httpCode == 402 -> TtsError.PaidPlanRequired
            httpCode == 404 -> TtsError.VoiceNotFound
            httpCode == 422 -> TtsError.Unknown(httpCode, detail.message)
            httpCode == 429 -> TtsError.RateLimited
            httpCode in 500..599 -> TtsError.ServerProblem(httpCode)
            else -> TtsError.Unknown(httpCode, detail.message)
        }
    }

    /** Connectivity failures never reach an HTTP code, so they arrive here instead. */
    fun mapFailure(throwable: Throwable): TtsError = when (throwable) {
        is IOException -> TtsError.Offline
        else -> TtsError.Unknown(0, throwable.message)
    }

    private fun byStatus(status: String?): TtsError? = when (status) {
        "missing_permissions" -> TtsError.MissingPermissions
        "invalid_api_key", "needs_authorization" -> TtsError.InvalidKey
        "ivc_not_permitted" -> TtsError.ClonedVoiceNotAllowed
        "paid_plan_required", "subscription_required" -> TtsError.PaidPlanRequired
        "quota_exceeded" -> TtsError.QuotaExceeded
        "voice_not_found" -> TtsError.VoiceNotFound
        "max_character_limit_exceeded" -> TtsError.TextTooLong
        "detected_unusual_activity" -> TtsError.AccountFlagged
        "too_many_concurrent_requests" -> TtsError.RateLimited
        else -> null
    }

    private data class Detail(
        val status: String? = null,
        val code: String? = null,
        val message: String? = null
    )

    private fun parseDetail(body: String?): Detail {
        if (body.isNullOrBlank()) return Detail()

        return runCatching {
            when (val detail = JSONObject(body).opt("detail")) {
                is JSONObject -> Detail(
                    status = detail.optStringOrNull("status"),
                    code = detail.optStringOrNull("code"),
                    message = detail.optStringOrNull("message")
                )

                is JSONArray -> Detail(
                    message = (detail.opt(0) as? JSONObject)?.optStringOrNull("msg")
                )

                is String -> Detail(message = detail)

                else -> Detail()
            }
        }.getOrDefault(Detail())
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
