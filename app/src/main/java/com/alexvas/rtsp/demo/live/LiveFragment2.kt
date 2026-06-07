package com.alexvas.rtsp.demo.live

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.alexvas.rtsp.codec.AudioDecodeThread
import com.alexvas.rtsp.demo.databinding.FragmentLive2Binding
import com.alexvas.rtsp.widget.RtspPlayer

@SuppressLint("LogNotTimber")
class LiveFragment2 : Fragment() {

    private lateinit var binding: FragmentLive2Binding
    private lateinit var liveViewModel: LiveViewModel
    private var rtspPlayer: RtspPlayer? = null
    private var avSyncAdjustmentUs: Long = DEFAULT_AV_SYNC_ADJUSTMENT_US


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentLive2Binding.inflate(inflater, container, false)
        binding.root.keepScreenOn = true

        initAvSyncControls()

        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                startPlay(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {

            }
        })

        return binding.root
    }

    private fun startPlay(surface: Surface) {
        val uri = liveViewModel.rtspRequest.value!!.toUri()
        rtspPlayer = RtspPlayer(requireContext(), surface, 1080, 1920)
        rtspPlayer!!.videoFrameRateStabilization = true
        rtspPlayer!!.audioPlaybackEnabled = true
        rtspPlayer!!.audioVideoSyncAdjustmentUs = avSyncAdjustmentUs
        rtspPlayer!!.init(
            uri,
            username = liveViewModel.rtspUsername.value,
            password = liveViewModel.rtspPassword.value,
            userAgent = "rtsp-client-android"
        )
        rtspPlayer!!.audioBufferListener = object : AudioDecodeThread.AudioBufferListener {
            override fun onAudioBufferAvailable(
                data: ByteArray,
                offset: Int,
                length: Int,
                presentationTimeUs: Long,
                sampleRate: Int,
                channelCount: Int
            ) {

            }
        }
        rtspPlayer!!.start(requestVideo = true, requestAudio = true, requestApplication = false)
    }

    private fun initAvSyncControls() {
        binding.sbAvSyncAdjustment.max = ((AV_SYNC_MAX_US - AV_SYNC_MIN_US) / AV_SYNC_STEP_US).toInt()
        binding.sbAvSyncAdjustment.progress = ((avSyncAdjustmentUs - AV_SYNC_MIN_US) / AV_SYNC_STEP_US).toInt()
        updateAvSyncLabel()
        binding.sbAvSyncAdjustment.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                avSyncAdjustmentUs = AV_SYNC_MIN_US + progress * AV_SYNC_STEP_US
                updateAvSyncLabel()
                rtspPlayer?.audioVideoSyncAdjustmentUs = avSyncAdjustmentUs
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateAvSyncLabel() {
        val ms = avSyncAdjustmentUs / 1000
        val sign = if (ms >= 0) "+" else ""
        binding.tvAvSyncAdjustment.text = "AV Sync: ${sign}${ms} ms (video delay)"
    }

    override fun onDestroy() {
        rtspPlayer?.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        liveViewModel.loadParams(requireContext())
    }

    override fun onPause() {
        super.onPause()
        liveViewModel.saveParams(requireContext())
    }

    companion object {
        private const val AV_SYNC_MIN_US = -200_000L
        private const val AV_SYNC_MAX_US = 1_500_000L
        private const val AV_SYNC_STEP_US = 1_000L
        private const val DEFAULT_AV_SYNC_ADJUSTMENT_US = 460_000L
    }

}
