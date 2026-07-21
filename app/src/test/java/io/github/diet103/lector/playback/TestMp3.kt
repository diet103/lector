package io.github.diet103.lector.playback

/**
 * Synthesizes a valid CBR MP3 stream: MPEG-1 Layer III, 44.1 kHz mono, 128 kbps — the same
 * framing ElevenLabs streams as `mp3_44100_128`. Payloads stay zeroed: the extractor only
 * parses the 4-byte frame headers, and Robolectric's shadow codec never truly decodes.
 * Generated in code rather than checked in as a binary so the fixture stays reviewable.
 */
object TestMp3 {

    /** 144 * 128000 / 44100, no padding bit — each frame carries ~26.1 ms of audio. */
    const val FRAME_SIZE = 417

    fun bytes(frames: Int = 40): ByteArray {
        val out = ByteArray(frames * FRAME_SIZE)
        for (frame in 0 until frames) {
            val base = frame * FRAME_SIZE
            out[base] = 0xFF.toByte()       // sync
            out[base + 1] = 0xFB.toByte()   // MPEG-1, Layer III, no CRC
            out[base + 2] = 0x90.toByte()   // 128 kbps, 44.1 kHz, no padding
            out[base + 3] = 0xC0.toByte()   // mono
        }
        return out
    }
}
