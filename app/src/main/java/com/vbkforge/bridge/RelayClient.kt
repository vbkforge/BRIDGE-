package com.vbkforge.bridge

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps one outgoing WebSocket open from the tablet to your relay while control is on.
 * Reconnects automatically (1 s, 2 s, 4 s ... up to 15 s) until [disconnect] is called.
 */
class RelayClient(private val listener: Listener) {

    interface Listener {
        /** A JSON-RPC request from Claude. Called on an OkHttp thread. */
        fun onRequest(rid: String, msg: JSONObject)
        /** Connection status for the UI. Called on the main thread. */
        fun onStatus(connected: Boolean, text: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .pingInterval(20, TimeUnit.SECONDS)    // detects dead connections, keeps NAT open
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    private var url: String = ""
    private var attempt = 0
    private val reconnect = Runnable { open() }

    fun connect(url: String) {
        wanted = true
        main.post {
            this.url = url
            attempt = 0
            main.removeCallbacks(reconnect)
            socket?.cancel()
            socket = null
            open()
        }
    }

    /** Immediately drops the connection and stops reconnecting. Safe from any thread. */
    fun disconnect() {
        wanted = false
        main.removeCallbacks(reconnect)
        socket?.cancel()
        socket = null
        main.post { listener.onStatus(false, "Not connected") }
    }

    fun send(rid: String, msg: JSONObject): Boolean {
        val s = socket ?: return false
        return s.send(JSONObject().put("rid", rid).put("msg", msg).toString())
    }

    private fun open() {
        if (!wanted) return
        listener.onStatus(false, if (attempt == 0) "Connecting to relay…" else "Reconnecting…")
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            wanted = false
            listener.onStatus(false, "Relay address is not valid. Check it on the main screen.")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (webSocket !== socket) return@post
                    attempt = 0
                    ActivityLog.add("Connected to relay. Claude can reach this tablet.")
                    listener.onStatus(true, "Connected — Claude can control the tablet")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket || !wanted) return
                val obj = try { JSONObject(text) } catch (e: Exception) { return }
                val rid = obj.optString("rid", "")
                val msg = obj.optJSONObject("msg") ?: return
                if (rid.isNotEmpty()) listener.onRequest(rid, msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { handleDrop(webSocket, "closed ($code${if (reason.isNotBlank()) " $reason" else ""})", code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when {
                    response != null && response.code == 404 ->
                        "relay answered 404 — check the relay address"
                    response != null -> "relay answered HTTP ${response.code}"
                    else -> t.message ?: t.javaClass.simpleName
                }
                main.post { handleDrop(webSocket, why, -1) }
            }
        })
    }

    private fun handleDrop(webSocket: WebSocket, why: String, code: Int) {
        if (webSocket !== socket || !wanted) return
        socket = null
        if (code == 4000) {
            // The relay closes us with 4000 when another device connects with the same token.
            wanted = false
            ActivityLog.add("Relay connection taken over by another device using the same connector.")
            listener.onStatus(false, "Another device took over this connection")
            return
        }
        val delayMs = minOf(15_000L, 1000L shl minOf(attempt, 4))
        attempt++
        if (attempt == 1 || attempt % 10 == 0) ActivityLog.add("Relay connection lost: $why. Retrying.")
        listener.onStatus(false, "Connection lost ($why). Retrying in ${delayMs / 1000} s…")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delayMs)
    }
}
