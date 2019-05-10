package com.quicks.morph.remote.rtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.voiceengine.WebRtcAudioManager
import org.webrtc.voiceengine.WebRtcAudioRecord
import org.webrtc.voiceengine.WebRtcAudioTrack
import org.webrtc.voiceengine.WebRtcAudioUtils
import java.util.concurrent.Executors

class PeerFactory(private val context: Context) {

    private val TAG = "PeerFactory"

    private val executor = Executors.newSingleThreadExecutor()

    private val eglBase by lazy {
        EglBase.create()
    }

    private val factory: PeerConnectionFactory by lazy {
        initFactory(context)
    }

    fun createPeer(
        iceServers: List<PeerConnection.IceServer>,
        listener: Peer.Listener,
        onPeer: (Peer) -> Unit
    ) = executor.execute {

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.enableDtlsSrtp = true

        val exec: (() -> Unit) -> Unit = {
            executor.execute {
                it()
            }
        }

        val peer = Peer(rtcConfig, factory, context, eglBase, listener, exec)

        onPeer(peer)
    }

    private fun initFactory(context: Context): PeerConnectionFactory {

        // Initialize field trials.
        var fieldTrials = ""
        fieldTrials += VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL
        fieldTrials += VIDEO_FRAME_EMIT_FIELDTRIAL

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(fieldTrials)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true)
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false)
        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false)
        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false)

        // Set audio record error callbacks.
        WebRtcAudioRecord.setErrorCallback(object : WebRtcAudioRecord.WebRtcAudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: $errorMessage")
            }
            override fun onWebRtcAudioRecordStartError(
                errorCode: WebRtcAudioRecord.AudioRecordStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: $errorCode. $errorMessage")
            }
            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordError: $errorMessage")
            }
        })

        WebRtcAudioTrack.setErrorCallback(object : WebRtcAudioTrack.ErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: $errorMessage")
            }
            override fun onWebRtcAudioTrackStartError(
                errorCode: WebRtcAudioTrack.AudioTrackStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: $errorCode. $errorMessage")
            }
            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackError: $errorMessage")
            }
        })

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        return PeerConnectionFactory.builder()
            .setOptions(null)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun dispose() {
        eglBase.release()
        factory.dispose()
        Log.d(TAG, "Peer Factory closed.")
    }

}