package com.alexvas.rtsp.codec

import android.media.*
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max


class AudioDecodeThread(
    private val mimeType: String,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val codecConfig: ByteArray?,
    private val audioFrameQueue: AudioFrameQueue,
    private val initialPlayAudio: Boolean = true,
    private val initialBufferListener: AudioBufferListener? = null,
    private val audioClockProviderListener: AudioClockProviderListener? = null,
    private val shouldDropDecodedAudio: (() -> Boolean)? = null,
) : Thread() {

    interface AudioBufferListener {
        fun onAudioBufferAvailable(
            data: ByteArray,
            offset: Int,
            length: Int,
            presentationTimeUs: Long,
            sampleRate: Int,
            channelCount: Int,
            pcmEncoding: Int,
            sampleMimeType: String,
        ) {}
    }

    interface AudioClockProviderListener {
        fun onAudioClockProviderUpdated(provider: AudioClockProvider?)
    }

    private var isRunning = true
    @Volatile private var playAudio = initialPlayAudio
    @Volatile private var bufferListener: AudioBufferListener? = initialBufferListener
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var writtenFramesTotal: Long = 0L
    @Volatile private var mappedWrittenFrames: Long = Long.MIN_VALUE
    @Volatile private var mappedWrittenMediaPtsUs: Long = Long.MIN_VALUE
    @Volatile private var lastKnownPlayedFrames: Long = 0L
    @Volatile private var playbackHeadWrapCount: Long = 0L
    @Volatile private var lastPlaybackHeadPos32: Long = 0L
    private val audioTimestamp = AudioTimestamp()
    private val internalAudioClockProvider = object : AudioClockProvider {
        override fun getCurrentPositionUs(): Long {
            val mediaPtsUs = mappedWrittenMediaPtsUs
            val writeFrames = mappedWrittenFrames
            val track = audioTrack
            if (track == null || mediaPtsUs == Long.MIN_VALUE || writeFrames == Long.MIN_VALUE) {
                return 0L
            }
            val playedFrames = getPlayedFrames(track)
            val pendingFrames = max(0L, writeFrames - playedFrames)
            val audioPosUs = mediaPtsUs - framesToDurationUs(pendingFrames)
            return audioPosUs
        }

        override fun getPendingDurationUs(): Long {
            val writeFrames = mappedWrittenFrames
            val track = audioTrack
            if (track == null || writeFrames == Long.MIN_VALUE) {
                return 0L
            }
            val playedFrames = getPlayedFrames(track)
            val pendingFrames = max(0L, writeFrames - playedFrames)
            return framesToDurationUs(pendingFrames)
        }
    }

    fun stopAsync() {
        if (DEBUG) Log.v(TAG, "stopAsync()")
        isRunning = false
        // Wake up sleep() code
        interrupt()
    }

    fun setPlayAudioEnabled(enabled: Boolean) {
        playAudio = enabled
        audioTrack?.let { track ->
            try {
                if (enabled) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                } else {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        track.flush()
                    }
                    resetClockMapping()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to toggle audio playback state", t)
            }
        }
        audioClockProviderListener?.onAudioClockProviderUpdated(
            if (enabled) internalAudioClockProvider else null
        )
    }

    fun setAudioBufferListener(listener: AudioBufferListener?) {
        bufferListener = listener
    }

    override fun run() {
        if (DEBUG) Log.d(TAG, "$name started")

        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Creating audio decoder
        val decoder = MediaCodec.createDecoderByType(mimeType)
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)

        if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
            val csd0 = codecConfig ?: getAacDecoderConfigData(MediaCodecInfo.CodecProfileLevel.AACObjectLC, sampleRate, channelCount)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        } else if (mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            // TODO: Add Opus support

//            val OPUS_IDENTIFICATION_HEADER = "OpusHead".toByteArray()
//            val OPUS_PRE_SKIP_NSEC = ByteBuffer.allocate(8).putLong(11971).array()
//            val OPUS_SEEK_PRE_ROLL_NSEC = ByteBuffer.allocate(8).putLong(80000000).array()

//            val csd0 = ByteBuffer.allocate(8+1+1+2+4+2+1)
//            csd0.put("OpusHead".toByteArray())
//            // Version
//            csd0.put(1)
//            // Number of channels
//            csd0.put(2)
//            // Pre-skip
//            csd0.putShort(0)
//            csd0.putInt(sampleRate)
//            // Output Gain
//            csd0.putShort(0)
//            // Channel Mapping Family
//            csd0.put(0)
            // Buffer buf = new Buffer();
//                // Magic Signature：固定头，占8个字节，为字符串OpusHead
//                buf.write("OpusHead".getBytes(StandardCharsets.UTF_8));
//                // Version：版本号，占1字节，固定为0x01
//                buf.writeByte(1);
//                // Channel Count：通道数，占1字节，根据音频流通道自行设置，如0x02
//                buf.writeByte(1);
//                // Pre-skip：回放的时候从解码器中丢弃的samples数量，占2字节，为小端模式，默认设置0x00,
//                buf.writeShortLe(0);
//                // Input Sample Rate (Hz)：音频流的Sample Rate，占4字节，为小端模式，根据实际情况自行设置
//                buf.writeIntLe(currentFormat.HZ);
//                //Output Gain：输出增益，占2字节，为小端模式，没有用到默认设置0x00, 0x00就好
//                buf.writeShortLe(0);
//                // Channel Mapping Family：通道映射系列，占1字节，默认设置0x00就好
//                buf.writeByte(0);
//                //Channel Mapping Table：可选参数，上面的Family默认设置0x00的时候可忽略
//            format.setByteBuffer("csd-0", ByteBuffer.wrap(OPUS_IDENTIFICATION_HEADER).order(ByteOrder.BIG_ENDIAN))
//            format.setByteBuffer("csd-1", ByteBuffer.wrap(OPUS_PRE_SKIP_NSEC).order(ByteOrder.BIG_ENDIAN))
//            format.setByteBuffer("csd-2", ByteBuffer.wrap(OPUS_SEEK_PRE_ROLL_NSEC).order(ByteOrder.LITTLE_ENDIAN))

            val csd0 = byteArrayOf(
                0x4f, 0x70, 0x75, 0x73, // "Opus"
                0x48, 0x65, 0x61, 0x64, // "Head"
                0x01,  // Version
                0x02,  // Channel Count
                0x00, 0x00,  // Pre skip
                0x80.toByte(), 0xbb.toByte(), 0x00, 0x00, // Sample rate 48000
                0x00, 0x00,  // Output Gain (Q7.8 in dB)
                0x00,  // Mapping Family
            )
            val csd1 = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val csd2 = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            format.setByteBuffer("csd-2", ByteBuffer.wrap(csd2))
        }

        decoder.configure(format, null, null, 0)
        decoder.start()

        // Creating audio playback device
        val outChannel = if (channelCount > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val outAudio = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, outChannel, outAudio)
//      Log.i(TAG, "sampleRate: $sampleRate, bufferSize: $bufferSize".format(sampleRate, bufferSize))
        val track = AudioTrack(
                AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                AudioFormat.Builder()
                        .setEncoding(outAudio)
                        .setChannelMask(outChannel)
                        .setSampleRate(sampleRate)
                        .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                0)
        audioTrack = track
        resetClockMapping()
        if (playAudio) {
            track.play()
        }
        audioClockProviderListener?.onAudioClockProviderUpdated(
            if (playAudio) internalAudioClockProvider else null
        )

        val bufferInfo = MediaCodec.BufferInfo()
        var currentPcmEncoding = outAudio
        var currentSampleMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
        while (isRunning) {
            val inIndex: Int = decoder.dequeueInputBuffer(10000L)
            if (inIndex >= 0) {
                // fill inputBuffers[inputBufferIndex] with valid data
                var byteBuffer: ByteBuffer?
                try {
                    byteBuffer = decoder.getInputBuffer(inIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
                byteBuffer?.rewind()

                // Preventing BufferOverflowException
//              if (length > byteBuffer.limit()) throw DecoderFatalException("Error")

                val audioFrame: FrameQueue.Frame?
                try {
                    audioFrame = audioFrameQueue.pop()
                    if (audioFrame == null) {
                        Log.d(TAG, "Empty audio frame")
                        // Release input buffer
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                    } else {
                        byteBuffer?.put(audioFrame.data, audioFrame.offset, audioFrame.length)
                        decoder.queueInputBuffer(inIndex, audioFrame.offset, audioFrame.length, audioFrame.timestampMs, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
//            Log.i(TAG, "inIndex: ${inIndex}")

            try {
//                Log.w(TAG, "outIndex: ${outIndex}")
                if (!isRunning) break
                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        currentPcmEncoding =
                            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                outAudio
                            }
                        currentSampleMimeType =
                            outputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_RAW
                        Log.d(TAG, "Decoder format changed: $outputFormat")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (DEBUG) Log.d(TAG, "No output from decoder available")
                    else -> {
                        if (outIndex >= 0) {
                            val byteBuffer: ByteBuffer? = decoder.getOutputBuffer(outIndex)

                            val chunk = if (bufferInfo.size > 0) ByteArray(bufferInfo.size) else ByteArray(0)
                            if (chunk.isNotEmpty()) {
                                byteBuffer?.get(chunk)
                            }
                            byteBuffer?.clear()

                            if (chunk.isNotEmpty() && shouldDropDecodedAudio?.invoke() != true) {
                                bufferListener?.onAudioBufferAvailable(
                                    chunk,
                                    0,
                                    chunk.size,
                                    bufferInfo.presentationTimeUs,
                                    sampleRate,
                                    channelCount,
                                    currentPcmEncoding,
                                    currentSampleMimeType,
                                )
                                if (playAudio) {
                                    val writtenBytes = track.write(chunk, 0, chunk.size)
                                    if (writtenBytes > 0) {
                                        updateClockMapping(bufferInfo.presentationTimeUs, writtenBytes)
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // All decoded frames have been rendered, we can stop playing now
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                break
            }
        }
        try {
            track.pause()
        } catch (_: Throwable) {
        }
        track.flush()
        track.release()
        audioTrack = null
        audioClockProviderListener?.onAudioClockProviderUpdated(null)
        resetClockMapping()

        try {
            decoder.stop()
            decoder.release()
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioFrameQueue.clear()
        if (DEBUG) Log.d(TAG, "$name stopped")
    }

    companion object {
        private val TAG: String = AudioDecodeThread::class.java.simpleName
        private const val DEBUG = false

        fun getAacDecoderConfigData(audioProfile: Int, sampleRate: Int, channels: Int): ByteArray {
            // AOT_LC = 2
            // 0001 0000 0000 0000
            var extraDataAac = audioProfile shl 11
            // Sample rate
            when (sampleRate) {
                7350 -> extraDataAac = extraDataAac or (0xC shl 7)
                8000 -> extraDataAac = extraDataAac or (0xB shl 7)
                11025 -> extraDataAac = extraDataAac or (0xA shl 7)
                12000 -> extraDataAac = extraDataAac or (0x9 shl 7)
                16000 -> extraDataAac = extraDataAac or (0x8 shl 7)
                22050 -> extraDataAac = extraDataAac or (0x7 shl 7)
                24000 -> extraDataAac = extraDataAac or (0x6 shl 7)
                32000 -> extraDataAac = extraDataAac or (0x5 shl 7)
                44100 -> extraDataAac = extraDataAac or (0x4 shl 7)
                48000 -> extraDataAac = extraDataAac or (0x3 shl 7)
                64000 -> extraDataAac = extraDataAac or (0x2 shl 7)
                88200 -> extraDataAac = extraDataAac or (0x1 shl 7)
                96000 -> extraDataAac = extraDataAac or (0x0 shl 7)
            }
            // Channels
            extraDataAac = extraDataAac or (channels shl 3)
            val extraData = ByteArray(2)
            extraData[0] = (extraDataAac and 0xff00 shr 8).toByte() // high byte
            extraData[1] = (extraDataAac and 0xff).toByte()         // low byte
            return extraData
        }
    }

    private fun resetClockMapping() {
        writtenFramesTotal = 0L
        mappedWrittenFrames = Long.MIN_VALUE
        mappedWrittenMediaPtsUs = Long.MIN_VALUE
        lastKnownPlayedFrames = 0L
        playbackHeadWrapCount = 0L
        lastPlaybackHeadPos32 = 0L
    }

    private fun updateClockMapping(presentationTimeUs: Long, writtenBytes: Int) {
        val bytesPerFrame = channelCount * 2
        if (bytesPerFrame <= 0 || writtenBytes <= 0) {
            return
        }
        val writtenFrames = writtenBytes / bytesPerFrame
        if (writtenFrames <= 0) {
            return
        }
        val beforeWriteFrames = writtenFramesTotal
        val afterWriteFrames = beforeWriteFrames + writtenFrames
        writtenFramesTotal = afterWriteFrames
        mappedWrittenFrames = afterWriteFrames
        mappedWrittenMediaPtsUs = presentationTimeUs + framesToDurationUs(writtenFrames.toLong())
    }

    private fun getPlayedFrames(track: AudioTrack): Long {
        // Prefer platform timestamp when available; fallback to playback head with 32-bit unwrap.
        val timestampFrames = try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING && track.getTimestamp(audioTimestamp)) {
                audioTimestamp.framePosition
            } else {
                Long.MIN_VALUE
            }
        } catch (_: Throwable) {
            Long.MIN_VALUE
        }
        if (timestampFrames != Long.MIN_VALUE) {
            lastKnownPlayedFrames = max(lastKnownPlayedFrames, timestampFrames)
            return lastKnownPlayedFrames
        }

        val head32 = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head32 < lastPlaybackHeadPos32) {
            playbackHeadWrapCount++
        }
        lastPlaybackHeadPos32 = head32
        val unwrapped = (playbackHeadWrapCount shl 32) + head32
        lastKnownPlayedFrames = max(lastKnownPlayedFrames, unwrapped)
        return lastKnownPlayedFrames
    }

    private fun framesToDurationUs(frames: Long): Long {
        if (sampleRate <= 0) {
            return 0L
        }
        return (frames * 1_000_000L) / sampleRate.toLong()
    }

}

