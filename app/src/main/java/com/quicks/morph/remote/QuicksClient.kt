package com.quicks.morph.remote

import org.webrtc.PeerConnection
import retrofit2.Call
import retrofit2.Callback

class QuicksClient(
    private val api: QuicksAPI
) {

    fun getIceServers(onResponse: (List<PeerConnection.IceServer>?) -> Unit) {

        api.getIceServers().enqueue(object : Callback<IceResponse> {

            override fun onFailure(call: Call<IceResponse>, t: Throwable) {
                t.printStackTrace()
                onResponse(null)
            }

            override fun onResponse(call: Call<IceResponse>, response: retrofit2.Response<IceResponse>) {
                val body = response.body()
                val servers = body?.let {
                    val iceServersWrapper = body.data
                    val credential = iceServersWrapper.servers.credential
                    val username = iceServersWrapper.servers.username
                    iceServersWrapper.servers.urls.map {
                        PeerConnection.IceServer.builder(it)
                            .setUsername(username)
                            .setPassword(credential)
                            .createIceServer()
                    }
                }
                onResponse(servers)
            }
        })
    }

}