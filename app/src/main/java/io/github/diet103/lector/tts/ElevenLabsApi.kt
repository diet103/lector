package io.github.diet103.lector.tts

import io.github.diet103.lector.model.UserAccount
import io.github.diet103.lector.model.VoiceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The two JSON endpoints Lector needs (PLAN §11: OkHttp direct, no Retrofit).
 *
 * The key is passed per call rather than pulled from storage, because onboarding has to validate
 * a key *before* deciding to save it. A successful [getUser] is itself the validation: it proves
 * the key is real and carries the `User: Read` scope.
 *
 * Parsing stays tolerant — unknown fields are ignored and missing ones fall back — so an API that
 * grows a field doesn't break a released build.
 */
class ElevenLabsApi(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    suspend fun getUser(apiKey: String): ApiResult<UserAccount> =
        get("$baseUrl/v1/user", apiKey) { json ->
            val subscription = json.optJSONObject("subscription") ?: JSONObject()
            UserAccount(
                tier = subscription.optString("tier", "unknown"),
                charactersUsed = subscription.optInt("character_count", 0),
                characterLimit = subscription.optInt("character_limit", 0),
                canUseInstantVoiceCloning = subscription.optBoolean("can_use_instant_voice_cloning", false),
                firstName = json.optString("first_name").takeIf { it.isNotBlank() }
            )
        }

    suspend fun getVoices(apiKey: String): ApiResult<List<VoiceSummary>> =
        get("$baseUrl/v2/voices?page_size=100", apiKey) { json ->
            val array = json.optJSONArray("voices")
            buildList {
                for (index in 0 until (array?.length() ?: 0)) {
                    val voice = array?.optJSONObject(index) ?: continue
                    val id = voice.optString("voice_id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        VoiceSummary(
                            voiceId = id,
                            name = voice.optString("name").takeIf { it.isNotBlank() } ?: id,
                            category = voice.optString("category", "unknown"),
                            previewUrl = voice.optString("preview_url").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

    private suspend fun <T> get(
        url: String,
        apiKey: String,
        parse: (JSONObject) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failed(ElevenLabsErrorMapper.map(response.code, body))
                }
                val parsed = runCatching { parse(JSONObject(body.orEmpty())) }.getOrNull()
                    ?: return@withContext ApiResult.Failed(
                        ElevenLabsErrorMapper.map(response.code, null)
                    )
                ApiResult.Ok(parsed)
            }
        } catch (failure: Exception) {
            ApiResult.Failed(ElevenLabsErrorMapper.mapFailure(failure))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.elevenlabs.io"
    }
}
