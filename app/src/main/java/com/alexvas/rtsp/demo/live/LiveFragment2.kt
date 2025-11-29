package com.alexvas.rtsp.demo.live

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.alexvas.rtsp.demo.databinding.FragmentLive2Binding
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspPlayer

@SuppressLint("LogNotTimber")
class LiveFragment2 : Fragment() {

    private lateinit var binding: FragmentLive2Binding
    private var rtspPlayer: RtspPlayer? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentLive2Binding.inflate(inflater, container, false)
        binding.root.keepScreenOn = true

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
        val uri = "rtsp://192.168.50.31:8600"
        rtspPlayer = RtspPlayer(requireContext(), surface, 1080, 1920)
        rtspPlayer!!.videoFrameRateStabilization = true
        rtspPlayer!!.audioPlaybackEnabled = true
        rtspPlayer!!.init(uri.toUri())
        rtspPlayer!!.setDataListener(object : RtspDataListener {
            override fun onRtspDataAudioSampleReceived(
                data: ByteArray,
                offset: Int,
                length: Int,
                timestamp: Long
            ) {

            }
        })
        rtspPlayer!!.start(requestVideo = true, requestAudio = true, requestApplication = false)
    }

    override fun onDestroy() {
        rtspPlayer?.stop()
        super.onDestroy()
    }

}
