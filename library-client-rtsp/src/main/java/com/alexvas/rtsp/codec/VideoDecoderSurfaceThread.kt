package com.alexvas.rtsp.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

class VideoDecoderSurfaceThread(
    private val surface: Surface,
    mimeType: String,
    width: Int,
    height: Int,
    rotation: Int, // 0, 90, 180, 270
    videoFrameQueue: VideoFrameQueue,
    videoDecoderListener: VideoDecoderListener,
    videoDecoderType: DecoderType = DecoderType.HARDWARE,
    videoFrameRateStabilization: Boolean = false,
) : VideoDecodeThread(
    mimeType, width, height, rotation, videoFrameQueue, videoDecoderListener, videoDecoderType
) {

    /**
     * Presentation time (in RTP units converted to microseconds) of the first frame used as the
     * PTS baseline.
     */
    private var streamStartPtsUs: Long? = null

    /**
     * Monotonic clock timestamp corresponding to streamStartPtsUs, used to map future frames
     * to real time.
     */
    private var playbackStartRealtimeNs: Long? = null

    /**
     * Timestamp of the most recently released frame to enforce minimum spacing between consecutive
     * frames.
     */
    private var lastFrameReleaseTimeNs: Long = Long.MIN_VALUE

    /**
     * Last presentation timestamp we processed; used to detect wrap-around or backwards jumps.
     */
    private var lastPresentationTimeUs: Long = Long.MIN_VALUE
    private var audioSyncBaseVideoPtsUs: Long = Long.MIN_VALUE
    private var audioSyncBaseAudioPosUs: Long = Long.MIN_VALUE
    private var lastAudioPositionUs: Long = Long.MIN_VALUE

    init {
        setVideoFrameRateStabilization(videoFrameRateStabilization)
    }

    override fun decoderCreated(mediaCodec: MediaCodec, mediaFormat: MediaFormat) {
        if (DEBUG) Log.v(TAG, "decoderCreated()")
        if (!surface.isValid) {
            Log.e(TAG, "Surface invalid")
        }
        mediaCodec.configure(mediaFormat, surface, null, 0)
        resetFrameTiming()
    }

    override fun setVideoSyncMode(mode: VideoSyncMode) {
        super.setVideoSyncMode(mode)
        if (mode != VideoSyncMode.AUDIO_MASTER) {
            resetAudioSyncTiming()
        }
    }

    override fun setAudioClockProvider(provider: AudioClockProvider?) {
        super.setAudioClockProvider(provider)
        if (provider == null) {
            resetAudioSyncTiming()
        }
    }

    private fun releaseOutputBufferWithFrameRateStabilization(
        mediaCodec: MediaCodec,
        outIndex: Int,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        if (DEBUG) Log.v(TAG, "releaseOutputBufferWithFrameRateStabilization(outIndex=$outIndex)")

        val ptsUs = bufferInfo.presentationTimeUs
        val nowNs = System.nanoTime()

        if (streamStartPtsUs == null || playbackStartRealtimeNs == null) {
            // First frame (or after a reset): initialize all timing anchors.
            streamStartPtsUs = ptsUs
            playbackStartRealtimeNs = nowNs
            lastFrameReleaseTimeNs = nowNs
            lastPresentationTimeUs = ptsUs
            mediaCodec.releaseOutputBuffer(outIndex, nowNs)
            return
        }

        var targetNs = playbackStartRealtimeNs!! + (ptsUs - streamStartPtsUs!!) * 1000L
        var adjustedNowNs = System.nanoTime()

        if (lastPresentationTimeUs != Long.MIN_VALUE && ptsUs < lastPresentationTimeUs) {
            // PTS went backwards (e.g. codec reordering). Re-base the clock to avoid negative deltas.
            streamStartPtsUs = ptsUs
            playbackStartRealtimeNs = adjustedNowNs
            targetNs = adjustedNowNs
        }

        if (lastFrameReleaseTimeNs != Long.MIN_VALUE) {
            // Ensure we never schedule two frames closer together than the min spacing.
            targetNs = max(targetNs, lastFrameReleaseTimeNs + MIN_FRAME_SPACING_NS)
        }

        adjustedNowNs = System.nanoTime()
        val latenessNs = adjustedNowNs - targetNs

        if (latenessNs >= FRAME_DROP_THRESHOLD_NS) {
            // Frame is critically late; drop to keep playback responsive.
            mediaCodec.releaseOutputBuffer(outIndex, false)
            lastFrameReleaseTimeNs = adjustedNowNs
            return
        }

        var correctedTargetNs = targetNs
        if (latenessNs > 0) {
            // For mild lateness, shift the playback baseline forward so future frames stay aligned.
            val correction = minOf(latenessNs, FRAME_DROP_THRESHOLD_NS)
            playbackStartRealtimeNs = playbackStartRealtimeNs?.plus(correction)
            correctedTargetNs += correction
        }

        if (correctedTargetNs <= adjustedNowNs + RENDER_EARLY_MARGIN_NS) {
            // Already at/behind the target time: render immediately using the current VSYNC.
            mediaCodec.releaseOutputBuffer(outIndex, true)
            lastFrameReleaseTimeNs = adjustedNowNs
        } else {
            // Still early enough: hand the desired release timestamp to MediaCodec for VSYNC alignment.
            mediaCodec.releaseOutputBuffer(outIndex, correctedTargetNs)
            lastFrameReleaseTimeNs = correctedTargetNs
        }

        lastPresentationTimeUs = ptsUs
    }

    private fun releaseOutputBufferWithAudioMasterSync(
        mediaCodec: MediaCodec,
        outIndex: Int,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        val provider = currentAudioClockProvider
        if (provider == null) {
            mediaCodec.releaseOutputBuffer(outIndex, true)
            return
        }

        val ptsUs = bufferInfo.presentationTimeUs
        val nowNs = System.nanoTime()

        val audioPosUs = try {
            provider.getCurrentPositionUs()
        } catch (_: Throwable) {
            INVALID_AUDIO_CLOCK_US
        }

        if (audioPosUs <= INVALID_AUDIO_CLOCK_US) {
            mediaCodec.releaseOutputBuffer(outIndex, true)
            return
        }

        if (audioSyncBaseVideoPtsUs == Long.MIN_VALUE || audioSyncBaseAudioPosUs == Long.MIN_VALUE) {
            audioSyncBaseVideoPtsUs = ptsUs
            audioSyncBaseAudioPosUs = audioPosUs
            lastAudioPositionUs = audioPosUs
            mediaCodec.releaseOutputBuffer(outIndex, true)
            return
        }

        if (lastAudioPositionUs != Long.MIN_VALUE && audioPosUs + AUDIO_CLOCK_BACKWARD_TOLERANCE_US < lastAudioPositionUs) {
            // Playback clock moved backwards significantly (seek/reset). Re-anchor sync.
            audioSyncBaseVideoPtsUs = ptsUs
            audioSyncBaseAudioPosUs = audioPosUs
        }
        lastAudioPositionUs = audioPosUs

        val audioMasterPtsUs = audioSyncBaseVideoPtsUs + (audioPosUs - audioSyncBaseAudioPosUs)
        val avDiffUs = ptsUs - audioMasterPtsUs
        val pendingUs = try {
            provider.getPendingDurationUs()
        } catch (_: Throwable) {
            -1L
        }
        val safePendingUs = maxOf(0L, pendingUs)
        val dynamicMaxAheadUs = (safePendingUs + AUDIO_PENDING_HEADROOM_US)
            .coerceIn(VIDEO_MIN_AHEAD_WAIT_US, VIDEO_MAX_AHEAD_WAIT_CAP_US)

        if (avDiffUs < -VIDEO_LATE_DROP_US) {
            mediaCodec.releaseOutputBuffer(outIndex, false)
            return
        }

        val clampedAheadUs = minOf(avDiffUs, dynamicMaxAheadUs)
        if (clampedAheadUs > VIDEO_EARLY_RENDER_MARGIN_US) {
            val delayNs = (clampedAheadUs - VIDEO_EARLY_RENDER_MARGIN_US) * 1000L
            releaseOutputBufferAfterDelay(mediaCodec, outIndex, nowNs + delayNs)
        } else {
            mediaCodec.releaseOutputBuffer(outIndex, true)
        }
    }

    private fun releaseOutputBufferAfterDelay(
        mediaCodec: MediaCodec,
        outIndex: Int,
        targetReleaseNs: Long
    ): Long {
        // Some Surface consumers do not strictly honor codec release timestamps.
        // Block on the decode thread to enforce wall-clock delay, then render immediately.
        val waitStartNs = System.nanoTime()
        var remainingNs = targetReleaseNs - System.nanoTime()
        while (remainingNs > 0L) {
            if (remainingNs > COARSE_WAIT_SWITCH_NS) {
                val sleepMs = (remainingNs - COARSE_WAIT_GUARD_NS) / 1_000_000L
                if (sleepMs > 0L) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        mediaCodec.releaseOutputBuffer(outIndex, false)
                        return -1L
                    }
                } else {
                    LockSupport.parkNanos(remainingNs)
                }
            } else {
                LockSupport.parkNanos(remainingNs)
            }
            remainingNs = targetReleaseNs - System.nanoTime()
        }
        mediaCodec.releaseOutputBuffer(outIndex, true)
        return System.nanoTime() - waitStartNs
    }

    override fun releaseOutputBuffer(
        mediaCodec: MediaCodec,
        outIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        render: Boolean
    ) {
        if (DEBUG) Log.v(TAG, "releaseOutputBuffer(outIndex=$outIndex, render=$render)")
        if (!render || !surface.isValid) {
            mediaCodec.releaseOutputBuffer(outIndex, false)
            return
        }

        if (currentVideoSyncMode == VideoSyncMode.AUDIO_MASTER && currentAudioClockProvider != null) {
            releaseOutputBufferWithAudioMasterSync(mediaCodec, outIndex, bufferInfo)
        } else if (!hasVideoFrameRateStabilization()) {
            mediaCodec.releaseOutputBuffer(outIndex, true)
        } else {
            releaseOutputBufferWithFrameRateStabilization(mediaCodec, outIndex, bufferInfo)
        }
    }

    override fun decoderDestroyed(mediaCodec: MediaCodec) {
        if (DEBUG) Log.v(TAG, "decoderDestroyed()")
        resetFrameTiming()
    }

    private fun resetFrameTiming() {
        if (DEBUG) Log.v(TAG, "resetFrameTiming()")
        streamStartPtsUs = null
        playbackStartRealtimeNs = null
        lastFrameReleaseTimeNs = Long.MIN_VALUE
        lastPresentationTimeUs = Long.MIN_VALUE
        resetAudioSyncTiming()
    }

    private fun resetAudioSyncTiming() {
        audioSyncBaseVideoPtsUs = Long.MIN_VALUE
        audioSyncBaseAudioPosUs = Long.MIN_VALUE
        lastAudioPositionUs = Long.MIN_VALUE
    }

    companion object {
        private val FRAME_DROP_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(80)
        private val MIN_FRAME_SPACING_NS = TimeUnit.MILLISECONDS.toNanos(1)
        private val RENDER_EARLY_MARGIN_NS = TimeUnit.MILLISECONDS.toNanos(2)
        private const val INVALID_AUDIO_CLOCK_US = 0L
        private const val AUDIO_CLOCK_BACKWARD_TOLERANCE_US = 30_000L
        private const val VIDEO_LATE_DROP_US = 160_000L
        private const val VIDEO_MIN_AHEAD_WAIT_US = 180_000L
        private const val VIDEO_MAX_AHEAD_WAIT_CAP_US = 400_000L
        private const val AUDIO_PENDING_HEADROOM_US = 30_000L
        private const val VIDEO_EARLY_RENDER_MARGIN_US = 6_000L
        private val COARSE_WAIT_SWITCH_NS = TimeUnit.MILLISECONDS.toNanos(3)
        private val COARSE_WAIT_GUARD_NS = TimeUnit.MILLISECONDS.toNanos(1)
    }

}
