package com.quicks.morph.remote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

class RtcManager(
    private val connAgent: ConnectionAgent,
    private val peerAgent: PeerConnectionClient,
    private val quicksClient: QuicksClient,
    private val enumerator: Camera2Enumerator
) : PeerConnectionClient.PeerConnectionEvents {

    private val TAG = "RtcManager"

    fun start() {

        quicksClient.getIceServers {

            peerAgent.createPeerConnection(tryCreateVideoCapturer(), it)
            peerAgent.createOffer()

            connAgent.subscribe { msg ->

                val json = JSONObject(msg)

                when (val type = json.optString("type")) {

                    "candidate" -> {
                        val candidate = IceCandidate(
                            json.getString("id"),
                            json.getInt("label"),
                            json.getString("candidate")
                        )
                        peerAgent.addRemoteIceCandidate(candidate)
                        Log.d(TAG, "WS RECEIVED CANDIDATE")
                    }

                    "answer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                        )
                        peerAgent.setRemoteDescription(sdp)
                        Log.d(TAG, "WS RECEIVED ANSWER")
                    }

                    else -> Log.d(TAG, "WS RECEIVED UNKNOWN TEXT: " + msg)
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

    override fun onIceConnected() {
        Log.d(TAG, "ICE CONNECTED")
    }

    override fun onIceDisconnected() {
        Log.d(TAG, "ICE DISCONNECTED")
    }

    override fun onPeerConnectionClosed() {
        Log.d(TAG, "PEER CONNECTION CLOSED")
    }

    override fun onPeerConnectionStatsReady(reports: Array<out StatsReport>?) {
        Log.d(TAG, "PEER CONNECTION STATUS REPORTS READY")
    }

    override fun onPeerConnectionError(description: String?) {
        Log.d(TAG, "PEER CONNECTION ERROR: $description")

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


    // Create VideoCapturer
    private fun tryCreateVideoCapturer(): VideoCapturer? {
        for (deviceName in enumerator.deviceNames) {
            Logging.d(TAG, "Creating front facing camera capturer.")
            val videoCapturer = enumerator.createCapturer(deviceName, cameraHandler)
            if (videoCapturer != null) {
                return videoCapturer
            }
        }
        return null
    }

    private val cameraHandler = object : CameraVideoCapturer.CameraEventsHandler {

        override fun onCameraError(err: String?) {
            Log.d(TAG, "CAMERA ERROR: $err")
        }

        override fun onCameraOpening(err: String?) {
            Log.d(TAG, "CAMERA OPENING: $err")
        }

        override fun onCameraDisconnected() {
            Log.d(TAG, "CAMERA DISCONNECTED")
        }

        override fun onCameraFreezed(err: String?) {
            Log.d(TAG, "CAMERA FREEZED: $err")
        }

        override fun onFirstFrameAvailable() {
            Log.d(TAG, "FIRST FRAME AVAILABLE")
        }

        override fun onCameraClosed() {
            Log.d(TAG, "CAMERA CLOSED")
        }
    }

}