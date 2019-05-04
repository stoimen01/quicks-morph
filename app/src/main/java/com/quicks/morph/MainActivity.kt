package com.quicks.morph

import android.app.Activity
import android.os.Bundle
import com.quicks.morph.remote.ConnectionAgent
import com.quicks.morph.remote.QuicksAPI
import com.quicks.morph.remote.QuicksClient
import com.quicks.morph.remote.RtcManager
import okhttp3.OkHttpClient
import org.appspot.apprtc.PeerConnectionClient
import org.webrtc.Camera2Enumerator
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

}
