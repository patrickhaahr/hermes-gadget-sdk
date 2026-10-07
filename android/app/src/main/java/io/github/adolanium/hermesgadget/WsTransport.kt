package io.github.adolanium.hermesgadget

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** What the transport reports, always delivered on the core thread. */
interface TransportEvents {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(data: ByteArray)
    fun onClosed(reason: String)
}

/**
 * The core's WebSocket. OkHttp calls back on its own threads; each event is handed
 * to [post] (the core thread) and dropped there if a newer connect() or close()
 * has superseded the socket it came from, like the simulator's WsTransport.
 */
class WsTransport(private val post: (Runnable) -> Unit, private val events: TransportEvents) {
    // The core runs its own heartbeat (ping/pong frames), so OkHttp's is off.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var generation = 0
    @Volatile private var socket: WebSocket? = null

    fun connect(url: String, subprotocol: String) {
        val gen = ++generation
        socket?.cancel()
        socket = null
        val request = Request.Builder().url(url)
            .apply { if (subprotocol.isNotEmpty()) header("Sec-WebSocket-Protocol", subprotocol) }
            .build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                post(Runnable {
                    if (gen != generation) {
                        webSocket.cancel()  // superseded while it was opening
                        return@Runnable
                    }
                    socket = webSocket
                    events.onOpen()
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) = deliver(gen) { events.onText(text) }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                deliver(gen) { events.onBinary(data) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                closed(gen, "closed ($code${if (reason.isNotEmpty()) ": $reason" else ""})")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                closed(gen, "${t.javaClass.simpleName}: ${t.message ?: "connection failed"}")
        })
    }

    fun sendText(text: String): Boolean = socket?.send(text) ?: false

    fun sendBinary(data: ByteArray): Boolean = socket?.send(data.toByteString()) ?: false

    fun close() {
        generation++
        socket?.close(1000, null)
        socket = null
    }

    fun shutdown() {
        close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun closed(gen: Int, reason: String) = deliver(gen) {
        socket = null
        events.onClosed(reason)
    }

    private fun deliver(gen: Int, event: () -> Unit) {
        post(Runnable { if (gen == generation) event() })
    }
}
