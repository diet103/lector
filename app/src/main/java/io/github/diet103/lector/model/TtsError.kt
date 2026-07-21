package io.github.diet103.lector.model

/**
 * Every way speaking can fail, phrased for a human (PLAN §3).
 *
 * [message] is written to be shown verbatim — it says what went wrong *and* what to do about it,
 * because these surface in a scrim over someone else's app where there is no room for a second
 * screen. [isKeyProblem] lets the UI offer "change key" only when that is actually the fix.
 */
sealed interface TtsError {

    val message: String

    val isKeyProblem: Boolean get() = false

    data object NoApiKey : TtsError {
        override val message = "No ElevenLabs key yet. Open Lector to finish setup."
        override val isKeyProblem = true
    }

    data object InvalidKey : TtsError {
        override val message = "ElevenLabs rejected that key. Check it in Lector's settings."
        override val isKeyProblem = true
    }

    /** Scoped keys are common and this is the single most likely first-run failure. */
    data object MissingPermissions : TtsError {
        override val message =
            "This key is missing permissions. It needs Text-to-Speech, User: Read and Voices: Read."
        override val isKeyProblem = true
    }

    data object QuotaExceeded : TtsError {
        override val message = "You're out of ElevenLabs characters for this period."
    }

    /** The account's own instantly-cloned voice on a plan that doesn't allow it. */
    data object ClonedVoiceNotAllowed : TtsError {
        override val message =
            "Cloned voices need a paid ElevenLabs plan. Pick a different voice in Lector."
    }

    /** Library voices are unavailable on the free tier even though they list fine. */
    data object PaidPlanRequired : TtsError {
        override val message =
            "That voice needs a paid ElevenLabs plan. Pick one from your own voices instead."
    }

    data object VoiceNotFound : TtsError {
        override val message = "That voice no longer exists on your account. Pick another in Lector."
    }

    data object TextTooLong : TtsError {
        override val message = "That's too much text for one go. Select a smaller piece."
    }

    data object AccountFlagged : TtsError {
        override val message = "ElevenLabs has flagged this account. Check it on their dashboard."
    }

    data object RateLimited : TtsError {
        override val message = "Too many requests at once. Wait a moment and try again."
    }

    data object Offline : TtsError {
        override val message = "No connection, so nothing could be fetched."
    }

    data class ServerProblem(val code: Int) : TtsError {
        override val message = "ElevenLabs is having trouble right now (error $code). Try again shortly."
    }

    /** Never swallow an unrecognised failure — show the code so a bug report is actionable. */
    data class Unknown(val code: Int, val detail: String?) : TtsError {
        override val message = buildString {
            append("Something went wrong")
            if (code > 0) append(" (error $code)")
            append(".")
            detail?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        }
    }
}
