package com.alexvas.rtsp.widget

import android.content.Context
import android.net.Uri
import android.view.Surface
import com.alexvas.rtsp.codec.AudioDecodeThread.AudioBufferListener
import com.alexvas.rtsp.codec.VideoDecodeThread.DecoderType
import com.alexvas.rtsp.codec.VideoDecoderSurfaceThread
import com.alexvas.rtsp.widget.RtspProcessor.Statistics
import com.limelight.binding.video.MediaCodecHelper

/**
 * Headless RTSP player that renders video to a supplied [Surface] and exposes decoded audio buffers.
 *
 * The API mirrors [RtspSurfaceView] closely while allowing the caller to manage the rendering surface.
 */
class RtspPlayer(
    context: Context,
    surface: Surface,
    surfaceWidth: Int = 1920,
    surfaceHeight: Int = 1080,
) {

    private var surface: Surface = surface
    private var surfaceWidth: Int = surfaceWidth
    private var surfaceHeight: Int = surfaceHeight

    private var rtspProcessor = createProcessor()

    init {
        MediaCodecHelper.initialize(context, /*glRenderer*/ "")
    }

    private fun createProcessor(): RtspProcessor {
        return RtspProcessor(
            onVideoDecoderCreateRequested = {
                    videoMimeType,
                    videoRotation,
                    videoFrameQueue,
                    videoDecoderListener,
                    videoDecoderType,
                    videoFrameRateStabilization,
                ->
                VideoDecoderSurfaceThread(
                    surface,
                    videoMimeType,
                    surfaceWidth,
                    surfaceHeight,
                    videoRotation,
                    videoFrameQueue,
                    videoDecoderListener,
                    videoDecoderType,
                    videoFrameRateStabilization,
                )
            }
        )
    }

    val statistics: Statistics
        get() = rtspProcessor.statistics

    var videoRotation: Int
        get() = rtspProcessor.videoRotation
        set(value) { rtspProcessor.videoRotation = value }

    var videoDecoderType: DecoderType
        get() = rtspProcessor.videoDecoderType
        set(value) { rtspProcessor.videoDecoderType = value }

    var experimentalUpdateSpsFrameWithLowLatencyParams: Boolean
        get() = rtspProcessor.experimentalUpdateSpsFrameWithLowLatencyParams
        set(value) { rtspProcessor.experimentalUpdateSpsFrameWithLowLatencyParams = value }

    var debug: Boolean
        get() = rtspProcessor.debug
        set(value) { rtspProcessor.debug = value }

    /** Enables decoder-side playback smoothing. Disabled by default. */
    var videoFrameRateStabilization: Boolean
        get() = rtspProcessor.videoFrameRateStabilization
        set(value) { rtspProcessor.videoFrameRateStabilization = value }

    /** Controls whether decoded audio should be rendered locally. */
    var audioPlaybackEnabled: Boolean
        get() = rtspProcessor.audioPlaybackEnabled
        set(value) { rtspProcessor.audioPlaybackEnabled = value }

    /** Listener receiving decoded PCM audio buffers. */
    var audioBufferListener: AudioBufferListener?
        get() = rtspProcessor.audioBufferListener
        set(value) { rtspProcessor.audioBufferListener = value }

    fun init(
        uri: Uri,
        username: String? = null,
        password: String? = null,
        userAgent: String? = null,
        socketTimeout: Int? = null,
    ) {
        rtspProcessor.init(
            uri,
            username,
            password,
            userAgent,
            socketTimeout ?: RtspProcessor.DEFAULT_SOCKET_TIMEOUT,
        )
    }

    fun start(requestVideo: Boolean, requestAudio: Boolean, requestApplication: Boolean = false) {
        rtspProcessor.start(requestVideo, requestAudio, requestApplication)
    }

    fun stop() {
        rtspProcessor.stop()
    }

    fun isStarted(): Boolean {
        return rtspProcessor.isStarted()
    }

    fun setStatusListener(listener: RtspStatusListener?) {
        rtspProcessor.statusListener = listener
    }

    fun setDataListener(listener: RtspDataListener?) {
        rtspProcessor.dataListener = listener
    }

    /**
     * Updates the rendering [Surface]. Callers should stop the stream before invoking this method.
     */
    fun setSurface(surface: Surface) {
        this.surface = surface
    }

    fun setSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }
}


