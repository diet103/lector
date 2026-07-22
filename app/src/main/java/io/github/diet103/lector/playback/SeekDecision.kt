package io.github.diet103.lector.playback

/**
 * What to do when the reader asks to start reading from a particular point.
 *
 * The whole tap-a-word feature failed for months because two components each held an opinion about
 * whether seeking was allowed and drifted apart. This is the one rule, in one place, evaluated by
 * [PlaybackService] at the instant of the tap — and pure, so it can be pinned by a test instead of
 * inferred from a device.
 *
 * The two inputs are not interchangeable and both are needed:
 *
 *  - **Is every byte on disk?** Only the cache can answer this. ElevenLabs ignores `Range`, so
 *    re-fetching at an offset would re-bill the whole text *and* splice a different generation.
 *  - **Does the loaded source have a seek map?** Only the player can answer this. Media3 builds a
 *    `ConstantBitrateSeekMap` for an MP3, but marks it non-seekable when the stream length was
 *    unknown at prepare time — which is exactly the case for a chunked TTS response.
 *    `ProgressiveMediaPeriod.seekToUs` then quietly rounds *every* seek to zero, which is what the
 *    user saw as "it just goes back to the beginning". A read prepared mid-stream keeps that
 *    non-seekable map for its whole life, even after the last byte has arrived — so the fix is to
 *    prepare it again from the finished file, not to wait.
 */
enum class SeekDecision {

    /** Still arriving. Seeking now would re-bill, so the tap is refused (the reader holds it). */
    REFUSE,

    /** Fully cached and the loaded source can seek: move the playhead, served from disk. */
    SEEK,

    /**
     * Fully cached, but this playback was prepared while the audio was still streaming and so has
     * no usable seek map. Prepare it again from the cached file, starting at the target.
     */
    REPREPARE;

    companion object {
        fun decide(fullyCached: Boolean, playerCanSeek: Boolean): SeekDecision = when {
            !fullyCached -> REFUSE
            playerCanSeek -> SEEK
            else -> REPREPARE
        }
    }
}
