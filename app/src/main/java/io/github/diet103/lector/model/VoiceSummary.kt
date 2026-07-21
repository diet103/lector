package io.github.diet103.lector.model

/** One voice from the account's own `/v2/voices` (PLAN §6 P6). */
data class VoiceSummary(
    val voiceId: String,
    val name: String,
    val category: String,
    val previewUrl: String?
) {
    /** Cloned voices need a paid plan; see [UserAccount.canUseInstantVoiceCloning]. */
    val isCloned: Boolean get() = category == "cloned" || category == "professional"
}
