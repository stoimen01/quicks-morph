package com.quicks.morph.remote

import android.os.Handler
import android.util.Log
import okhttp3.*
import okio.ByteString

/**
 * This agent takes care of keeping connection to the server
 * by reconnecting on failures.
 * */
class ConnectionAgent(
    private val okHttpClient: OkHttpClient
) : WebSocketListener() {

    private val tag = "ConnectionAgent"

    private val subscribers: MutableList<(String) -> Unit> = mutableListOf()

    private var ws: WebSocket? = null

    private fun tryConnect() {
        val request = Request.Builder()
            //.addHeader("Authorization", "Bearer $token")
            .url("ws://10.0.2.2:8080/ws")
            .build()
        ws = okHttpClient.newWebSocket(request, this)
    }

    fun subscribe(block: (String) -> Unit): () -> Unit {

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
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d(tag, "TEXT MESSAGE RECEIVED")
        subscribers.forEach { it(text) }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(tag, "BYTE MESSAGE RECEIVED")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(tag, "CONNECTION CLOSED. CODE: $code, REASON: $reason")
        ws = null
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response) {
        Log.d(tag, "CONNECTION FAILED: $t")
        webSocket.cancel()
        ws = null
        Handler().postDelayed({
            tryConnect()
        }, 3000)
    }

}