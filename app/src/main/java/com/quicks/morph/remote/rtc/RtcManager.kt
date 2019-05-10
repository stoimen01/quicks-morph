package com.quicks.morph.remote.rtc

import android.util.Log
import com.quicks.morph.remote.ConnectionAgent
import com.quicks.morph.remote.ConnectionAgent.Message.*
import com.quicks.morph.remote.QuicksClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate

class RtcManager(
    private val connAgent: ConnectionAgent,
    private val peerFactory: PeerFactory,
    private val quicksClient: QuicksClient
) : Peer.Listener {

    private val TAG = "RTC"

    private var peer: Peer? = null

    private val candidatesQueue: MutableList<IceCandidate> = mutableListOf()

    fun start() {
        connAgent.subscribe { msg ->
            return@subscribe when (msg) {
                Connected -> {
                }
                is Candidate -> {
                    peer?.addRemoteIceCandidate(msg.candidate) ?: run {
                        candidatesQueue.add(msg.candidate)
                        Unit
                    }
                }
                is Offer -> {
                    quicksClient.getIceServers {
                        if (it == null) return@getIceServers
                        peerFactory.createPeer(it, this,
                            onPeer = { pl ->
                                peer = pl

                                peer?.createAnswer(
                                    msg.sdp,
                                    onAnswer = { sdp ->
                                        Log.d(TAG, "SENDING ANSWER")
                                        val json = JSONObject()
                                        json.put("sdp", sdp.description)
                                        json.put( "type", "answer")
                                        connAgent.send(json.toString())
                                    },
                                    onError = {
                                        peer?.dispose()
                                        Log.d(TAG, "ERROR CREATING ANSWER")
                                    }
                                )

                                candidatesQueue.forEach { c ->
                                    peer?.addRemoteIceCandidate(c)
                                }

                            }
                        )
                    }
                }
                Closed -> {
                    peer?.dispose()
                }
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "SENDING ICE CANDIDATE")
        logIceCandidate(candidate)
        val json = JSONObject()
        json.put("type", "candidate")
        json.put("label", candidate.sdpMLineIndex)
        json.put("id", candidate.sdpMid)
        json.put( "candidate", candidate.sdp)
        connAgent.send(json.toString())
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
        Log.d(TAG, "SENDING REMOVED ICE CANDIDATES")
        candidates.forEach(this::logIceCandidate)
        val json = JSONObject()
        json.put("type", "remove-candidates")
        val jsonArray = JSONArray()
        for (candidate in candidates) {
            jsonArray.put(candidate.toJson())
        }
        json.put("candidates", jsonArray)
        connAgent.send(json.toString())
    }

    private fun logIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "url :" + candidate.serverUrl)
        Log.d(TAG, "sdpMid :" + candidate.sdpMid)
        Log.d(TAG, "sdpMLineIndex :" + candidate.sdpMLineIndex)
        Log.d(TAG, "sdp :" + candidate.sdp)
    }

    private fun IceCandidate.toJson(): JSONObject {
        val json = JSONObject()
        json.put("label", sdpMLineIndex)
        json.put("id", sdpMid)
        json.put("candidate", sdp)
        return json
    }

}