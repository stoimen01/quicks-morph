package com.quicks.morph

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.quicks.morph.remote.ConnectionAgent
import com.quicks.morph.remote.QuicksAPI
import com.quicks.morph.remote.QuicksClient
import com.quicks.morph.remote.RtcManager
import okhttp3.OkHttpClient
import com.quicks.morph.remote.PeerConnectionClient
import com.quicks.morph.remote.rtc.PeerAgent
import com.quicks.morph.remote.rtc.VIDEO_FRAME_EMIT_FIELDTRIAL
import com.quicks.morph.remote.rtc.VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL
import org.webrtc.*
import org.webrtc.voiceengine.WebRtcAudioManager
import org.webrtc.voiceengine.WebRtcAudioRecord
import org.webrtc.voiceengine.WebRtcAudioTrack
import org.webrtc.voiceengine.WebRtcAudioUtils
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

val peerConnectionParameters = PeerConnectionClient.PeerConnectionParameters(
    true,
    false,
    false,
    0,
    0,
    0,
    1700,
    "VP8",
    true,
    false,
    32,
    "OPUS",
    false,
    false,
    false,
    false,
    false,
    false,
    false,
    false,
    null
)

private const val TAG = "MainActivity"

class MainActivity : Activity() {

    private val peerConnectionClient = PeerConnectionClient()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val client: QuicksAPI by lazy {
        Retrofit.Builder()
            .baseUrl("127.0.0.1:8080")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuicksAPI::class.java)
    }

    private val connAgent = ConnectionAgent(okHttpClient)

    var eglBase: EglBase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eglBase = EglBase.create()

        PeerAgent(createFactory(this, eglBase!!))

        val manager = RtcManager(
            connAgent,
            peerConnectionClient,
            QuicksClient(client),
            Camera2Enumerator(this)
        )

        peerConnectionClient.createPeerConnectionFactory(
            applicationContext, peerConnectionParameters, manager
        )

        manager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        eglBase?.release()
    }
}


fun createFactory(context: Context, rootEglBase: EglBase): PeerConnectionFactory {

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

    val encoderFactory = DefaultVideoEncoderFactory(
        rootEglBase.eglBaseContext, true, false
    )
    val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

    return PeerConnectionFactory.builder()
        .setOptions(null)
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()
}
