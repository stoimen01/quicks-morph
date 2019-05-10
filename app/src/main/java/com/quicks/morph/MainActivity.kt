package com.quicks.morph

import android.app.Activity
import android.os.Bundle
import com.quicks.morph.remote.ConnectionAgent
import com.quicks.morph.remote.QuicksAPI
import com.quicks.morph.remote.QuicksClient
import com.quicks.morph.remote.rtc.PeerFactory
import com.quicks.morph.remote.rtc.RtcManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val client: QuicksAPI by lazy {
        Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8080/ice/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuicksAPI::class.java)
    }

    private val connAgent = ConnectionAgent(okHttpClient)

    private var peerFactory: PeerFactory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        peerFactory = PeerFactory(this)

        RtcManager(
            connAgent,
            peerFactory!!,
            QuicksClient(client)
        ).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        peerFactory?.dispose()
    }
}
