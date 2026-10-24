package io.github.qyxx2.mpdserver.remote.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

interface RealtimeSocket {
    fun close()
    fun cancel()
}

interface RealtimeSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed()
    fun onFailure(error: Throwable)
}

fun interface RealtimeSocketFactory {
    fun connect(url: String, listener: RealtimeSocketListener): RealtimeSocket
}

class OkHttpRealtimeSocketFactory(client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()) : RealtimeSocketFactory {
    private val client = client
    override fun connect(url: String, listener: RealtimeSocketListener): RealtimeSocket {
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
        })
        return object : RealtimeSocket {
            override fun close() { socket.close(1000, "client closing") }
            override fun cancel() { socket.cancel() }
        }
    }
}
