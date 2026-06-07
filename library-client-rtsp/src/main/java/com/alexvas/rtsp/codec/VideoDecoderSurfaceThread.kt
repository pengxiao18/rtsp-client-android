package com.alexvas.rtsp.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
    private val audioVideoSyncAdjustmentUs: Long = 0L,
    private val audioPlaybackClockUsProvider: (() -> Long?)? = null,
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

    /**
     * Constant offset used to map video RTP timeline to the audio playback clock timeline.
     * The offset is calibrated from the first valid audio/video pair and re-calibrated if a
     * large discontinuity is observed.
     */
    private var avSyncOffsetUs: Long? = null

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

        val audioClockUs = audioPlaybackClockUsProvider?.invoke()
        if (audioClockUs != null) {
            releaseOutputBufferWithAudioClock(mediaCodec, outIndex, bufferInfo, audioClockUs)
        } else if (!hasVideoFrameRateStabilization()) {
            mediaCodec.releaseOutputBuffer(outIndex, true)
        } else {
            releaseOutputBufferWithFrameRateStabilization(mediaCodec, outIndex, bufferInfo)
        }
    }

    private fun releaseOutputBufferWithAudioClock(
        mediaCodec: MediaCodec,
        outIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        audioClockUs: Long
    ) {
        val videoPtsUs = bufferInfo.presentationTimeUs
        val measuredOffsetUs = audioClockUs - videoPtsUs
        if (avSyncOffsetUs == null) {
            avSyncOffsetUs = measuredOffsetUs
        } else if (abs(measuredOffsetUs - avSyncOffsetUs!!) > AUDIO_SYNC_RESYNC_THRESHOLD_US) {
            // Recover from large timeline jumps (seek/stream reset/decoder restart jitter).
            avSyncOffsetUs = measuredOffsetUs
        }

        val alignedVideoUs = videoPtsUs + avSyncOffsetUs!!
        val deltaUs = alignedVideoUs - audioClockUs + audioVideoSyncAdjustmentUs
        when {
            deltaUs < -AUDIO_SYNC_DROP_THRESHOLD_US -> {
                mediaCodec.releaseOutputBuffer(outIndex, false)
            }
            deltaUs <= AUDIO_SYNC_RENDER_EARLY_MARGIN_US -> {
                mediaCodec.releaseOutputBuffer(outIndex, true)
            }
            else -> {
                val targetReleaseNs = System.nanoTime() + (deltaUs - AUDIO_SYNC_RENDER_EARLY_MARGIN_US) * 1000L
                mediaCodec.releaseOutputBuffer(outIndex, targetReleaseNs)
            }
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
        avSyncOffsetUs = null
    }

    companion object {
        private val FRAME_DROP_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(80)
        private val MIN_FRAME_SPACING_NS = TimeUnit.MILLISECONDS.toNanos(1)
        private val RENDER_EARLY_MARGIN_NS = TimeUnit.MILLISECONDS.toNanos(2)
        private const val AUDIO_SYNC_DROP_THRESHOLD_US = 80_000L
        private const val AUDIO_SYNC_RENDER_EARLY_MARGIN_US = 10_000L
        private const val AUDIO_SYNC_RESYNC_THRESHOLD_US = 2_000_000L
    }

}
