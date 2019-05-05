package com.quicks.morph.remote

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * This agent takes care of keeping connection to the server
 * by reconnecting on failures.
 * */
class ConnectionAgent(
    private val okHttpClient: OkHttpClient
) : WebSocketListener() {

    sealed class Message {
        data class Candidate(val candidate: IceCandidate) : Message()
        data class Answer(val sdp: SessionDescription) : Message()
        object Connected : Message()
        object Closed : Message()
    }

    private val tag = "ConnectionAgent"

    private val subscribers: MutableList<(Message) -> Unit> = mutableListOf()

    private var ws: WebSocket? = null

    private fun tryConnect() {
        val request = Request.Builder()
            //.addHeader("Authorization", "Bearer $token")
            .url("ws://10.0.2.2:8080/ws")
            .build()
        ws = okHttpClient.newWebSocket(request, this)
    }

    fun subscribe(block: (Message) -> Unit): () -> Unit {

        subscribers += block

        if (ws == null) {
            tryConnect()
        }

        return {
            subscribers.remove(block)
            if (subscribers.size <= 0) {
                ws?.cancel()
            }
        }
    }

    fun send(msg: String) = ws?.send(msg)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(tag, "CONNECTION OPEN")
        subscribers.forEach { it(Message.Connected) }
    }

    override fun onMessage(webSocket: WebSocket, msg: String) {
        Log.d(tag, "TEXT MESSAGE RECEIVED")

        val json = JSONObject(msg)

        val message = when (val type = json.optString("type")) {

            "candidate" -> {
                val candidate = IceCandidate(
                    json.getString("id"),
                    json.getInt("label"),
                    json.getString("candidate")
                )
                Message.Candidate(candidate)
            }

            "answer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                )
                Message.Answer(sdp)
            }

            else -> {
                throw IllegalArgumentException("Unexpected message !")
            }
        }

        subscribers.forEach { it(message) }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(tag, "BYTE MESSAGE RECEIVED")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(tag, "CONNECTION CLOSED. CODE: $code, REASON: $reason")
        ws = null
        subscribers.forEach { it(Message.Closed) }
    }

    override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
        Log.d(tag, "CONNECTION FAILED: $t")
        webSocket?.cancel()
        ws = null
        Thread.sleep(3000)
        tryConnect()
        subscribers.forEach { it(Message.Closed) }
    }

}