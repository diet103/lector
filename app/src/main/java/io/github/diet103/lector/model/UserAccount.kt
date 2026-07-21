package io.github.diet103.lector.model

/**
 * What `/v1/user` tells us about the key the user pasted (PLAN §6 P5).
 *
 * A successful fetch is itself the key validation — it proves the key exists *and* carries the
 * `User: Read` scope. The quota numbers are shown during onboarding so the figures can be checked
 * against the ElevenLabs dashboard, which is the phase's exit criterion.
 */
data class UserAccount(
    val tier: String,
    val charactersUsed: Int,
    val characterLimit: Int,
    /**
     * `subscription.can_use_instant_voice_cloning`. False on the free tier, and the reason a
     * user's own cloned voice lists in `/v2/voices` but fails at synthesis — the voice picker
     * uses this to keep them from choosing a voice that can never work.
     */
    val canUseInstantVoiceCloning: Boolean,
    val firstName: String?
) {
    val charactersRemaining: Int get() = (characterLimit - charactersUsed).coerceAtLeast(0)
}
