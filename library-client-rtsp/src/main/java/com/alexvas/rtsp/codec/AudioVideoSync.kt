package com.alexvas.rtsp.codec

/**
 * Source of the audio playback clock used for video-to-audio synchronization.
 *
 * Implementations are expected to return values from a monotonic timeline in microseconds.
 */
interface AudioClockProvider {
    /**
     * Audio playback head position in microseconds.
     */
    fun getCurrentPositionUs(): Long

    /**
     * Pending (queued but not yet played) audio duration in microseconds.
     */
    fun getPendingDurationUs(): Long = 0L
}

/**
 * Video sync mode for [VideoDecodeThread].
 */
enum class VideoSyncMode {
    /**
     * Legacy behavior: no explicit cross-track sync with external audio clock.
     */
    LEGACY,

    /**
     * Audio-master mode: video timing is aligned to [AudioClockProvider].
     */
    AUDIO_MASTER,
}

/**
 * Startup gating behavior for decoded audio delivery.
 */
enum class AudioStartupSyncMode {
    /**
     * Legacy behavior: forward decoded audio buffers immediately.
     */
    LEGACY,

    /**
     * Drop decoded audio buffers until the first video frame is rendered.
     * A timeout in [com.alexvas.rtsp.widget.RtspProcessor.audioStartupDropTimeoutMs]
     * can be used to avoid prolonged mute when video is delayed or unavailable.
     */
    DROP_UNTIL_FIRST_VIDEO_FRAME_RENDERED,
}

