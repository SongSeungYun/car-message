package com.hyundaiproject.messaging

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hyundaiproject.messaging.MessageGateway.Incoming.Kind
import com.hyundaiproject.messaging.MessageGateway.Result
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 중계 서버(be/)에 붙는 MessageGateway. 규약은 docs/protocol.md. 콜백은 OkHttp 스레드에서 온다. */
internal class WebSocketMessageGateway(
    private val serverUrl: String, // 예: ws://10.0.2.2:8080
    private val carId: String,     // A, B
) : MessageGateway {
    /** 받은 메시지 문구. 맥 음성 브리지로 읽을 때 쓴다 */
    var onReceived: (String) -> Unit = {}

    private val handler = Handler(Looper.getMainLooper()) // 재접속 예약 전용 (MainActivity가 자기 핸들러를 통째로 비우므로 따로 둔다)
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val waiting = LinkedHashMap<String, Pair<String, MessageGateway.SendCallback>>() // id → (send JSON, 콜백)
    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 200
    }
    private var receiver: MessageGateway.Receiver? = null
    private var current: WebSocket? = null
    private var open = false
    private var backoffMs = 1_000L
    private var closed = false

    override fun send(outgoing: MessageGateway.Outgoing, callback: MessageGateway.SendCallback): MessageGateway.Cancellation {
        val id = outgoing.requestId()
        val json = JSONObject().put("type", "send").put("id", id).put("code", outgoing.messageCode()).toString()
        val socket = synchronized(lock) {
            waiting[id] = json to callback
            current.takeIf { open }
        }
        socket?.send(json) // 연결 전이면 onOpen에서 보낸다
        return MessageGateway.Cancellation { synchronized(lock) { waiting.remove(id) } }
    }

    override fun subscribe(receiver: MessageGateway.Receiver): MessageGateway.Cancellation {
        synchronized(lock) { this.receiver = receiver }
        return MessageGateway.Cancellation {
            synchronized(lock) { if (this.receiver === receiver) this.receiver = null }
        }
    }

    fun close() {
        val socket = synchronized(lock) {
            closed = true
            current.also { current = null }
        }
        socket?.close(1000, null)
        handler.removeCallbacksAndMessages(null)
    }

    private fun connect() = synchronized(lock) {
        if (closed) return@synchronized
        val request = Request.Builder().url("$serverUrl/ws/$carId").build()
        current = client.newWebSocket(request, listener)
    }

    private fun lost(socket: WebSocket) {
        val delay = synchronized(lock) {
            if (socket !== current) return
            current = null
            open = false
            if (closed) return
            backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(10_000) }
        }
        handler.postDelayed({ connect() }, delay)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val pending = synchronized(lock) {
                if (webSocket !== current) return
                open = true
                backoffMs = 1_000
                waiting.values.map { it.first }
            }
            Log.i(TAG, "connected as $carId")
            pending.forEach(webSocket::send) // 끊긴 사이 "전송 중"이던 것은 같은 id로 다시 (protocol.md 흐름 6)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "connection lost: ${t.message}")
            lost(webSocket)
        }
    }

    init {
        connect() // listener보다 아래에 둔다: Kotlin은 위에서부터 초기화한다
    }

    private fun handle(socket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = message.optString("id")
        when (message.optString("type")) {
            "status" -> {
                val callback = synchronized(lock) { waiting.remove(id) }?.second ?: return
                callback.onResult(if (message.optString("state") == "delivered") Result.SUCCESS else Result.FAILURE)
            }
            "msg" -> {
                // 중복이어도, 모르는 코드여도 ack는 보낸다 (protocol.md 흐름 4)
                socket.send(JSONObject().put("type", "ack").put("id", id).toString())
                val (first, target) = synchronized(lock) { (seen.put(id, Unit) == null) to receiver }
                val preset = MessageCatalog.find(message.optString("code"))
                if (!first || preset == null) return
                val kind = if (MessageCatalog.critical(preset.code())) Kind.CRITICAL else Kind.NOTICE
                target?.onMessage(MessageGateway.Incoming(kind, preset.text(), message.optString("from") + "차"))
                onReceived(preset.text())
            }
            "error" -> Log.w(TAG, "server error: $text")
        }
    }

    private companion object {
        const val TAG = "CarMessage"
    }
}
