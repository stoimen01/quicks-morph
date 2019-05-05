package com.quicks.morph.remote.rtc

import android.util.Log
import com.quicks.morph.remote.ConnectionAgent
import com.quicks.morph.remote.ConnectionAgent.Message.*
import com.quicks.morph.remote.QuicksClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class RtcManager(
    private val connAgent: ConnectionAgent,
    private val peerAgent: PeerAgent,
    private val quicksClient: QuicksClient
) : PeerAgent.Listener {

    private val TAG = "RtcManager"

    fun start() {
        connAgent.subscribe { msg ->
            return@subscribe when (msg) {
                Connected -> {
                    quicksClient.getIceServers {
                        if (it == null) return@getIceServers
                        peerAgent.createPeerConnection(it, this)
                        peerAgent.createOffer()
                    }
                }
                is Candidate -> {
                    peerAgent.addRemoteIceCandidate(msg.candidate)
                }
                is Answer -> {
                    peerAgent.setRemoteDescription(msg.sdp)
                }
                Closed -> {
                    peerAgent.cleanUp()
                }
            }
        }
    }

    override fun onLocalDescription(sdp: SessionDescription) {
        Log.d(TAG, "Received local desc : ")
        Log.d(TAG, "type: " + sdp.type.name)
        Log.d(TAG, "desc: " + sdp.description)

        val json = JSONObject()
        json.put("sdp", sdp.description)
        json.put( "type", "offer")
        connAgent.send(json.toString())
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "Ice candidate received: ")
        logIceCandidate(candidate)
        val json = JSONObject()
        json.put("type", "candidate")
        json.put("label", candidate.sdpMLineIndex)
        json.put("id", candidate.sdpMid)
        json.put( "candidate", candidate.sdp)
        connAgent.send(json.toString())
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
        Log.d(TAG, "Ice candidates removed: ")
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