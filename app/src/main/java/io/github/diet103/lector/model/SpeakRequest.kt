package io.github.diet103.lector.model

data class SpeakRequest(
    val text: String,
    val voiceId: String,
    val modelId: String = "eleven_flash_v2_5",
    val outputFormat: String = "mp3_44100_128"
)
