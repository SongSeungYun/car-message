package carmessage

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val CODE = Regex("^[A-Z_]{1,40}$")
private const val MAX_ID = 64
private const val DELIVERED_KEEP = 1000

/** 차량 간 메시지 중계. 상태는 전부 메모리에만 둔다 (재시작하면 차량이 다시 접속하면 끝). */
class Relay(private val scope: CoroutineScope, private val ackTimeout: Duration) {
    private val log = LoggerFactory.getLogger("relay")

    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    private class Pending(val from: String, var timer: Job? = null)

    // ponytail: 전역 Mutex 하나. 차량 수천 대 규모가 되면 id별 락으로 나눈다.
    private val lock = Mutex()
    private val pending = HashMap<String, Pending>()
    private val delivered = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > DELIVERED_KEEP
    }

    suspend fun connect(vehicleId: String, session: WebSocketSession) {
        sessions.put(vehicleId, session)?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
        log.info("connect {}", vehicleId)
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) handle(vehicleId, frame.readText())
            }
        } finally {
            sessions.remove(vehicleId, session)
            // 끊긴 차량이 보낸 메시지는 결과를 받을 곳이 없으니 버린다 (protocol.md 흐름 6)
            lock.withLock {
                pending.entries.removeIf { (_, p) -> (p.from == vehicleId).also { if (it) p.timer?.cancel() } }
            }
            log.info("disconnect {}", vehicleId)
        }
    }

    private suspend fun handle(from: String, text: String) {
        val m = runCatching { json.decodeFromString(Inbound.serializer(), text) }.getOrNull()
            ?: return sendTo(from, ErrorMsg(reason = "BAD_FORMAT"))
        when (m) {
            is Send -> onSend(from, m)
            is Ack -> onAck(m)
        }
    }

    private suspend fun onSend(from: String, m: Send) {
        if (m.id.isBlank() || m.id.length > MAX_ID || !CODE.matches(m.code)) {
            return sendTo(from, ErrorMsg(m.id, "BAD_FORMAT"))
        }
        val targets = if (m.to != null) listOf(m.to).filter { it != from && sessions.containsKey(it) } else sessions.keys - from

        val proceed = lock.withLock {
            when {
                m.id in delivered -> "delivered"
                m.id in pending -> "pending"
                targets.isEmpty() -> "no_target"
                else -> {
                    val p = Pending(from)
                    pending[m.id] = p
                    p.timer = scope.launch {
                        delay(ackTimeout)
                        if (lock.withLock { pending.remove(m.id) } != null) {
                            log.info("timeout {} from {}", m.id, from)
                            sendTo(from, Status(m.id, "failed", "TIMEOUT"))
                        }
                    }
                    "send"
                }
            }
        }

        when (proceed) {
            "delivered" -> sendTo(from, Status(m.id, "delivered")) // 재전송인데 이미 도착함
            "pending" -> Unit                                       // 결과는 원래 타이머가 알려준다
            "no_target" -> sendTo(from, Status(m.id, "failed", "NO_TARGET"))
            "send" -> {
                log.info("send {} {} {} -> {}", m.id, m.code, from, targets)
                val msg = Msg(m.id, from, m.code, m.replyTo, System.currentTimeMillis())
                targets.forEach { sendTo(it, msg) }
            }
        }
    }

    private suspend fun onAck(m: Ack) {
        val p = lock.withLock {
            pending.remove(m.id)?.also { delivered[m.id] = Unit }
        } ?: return // 늦은 ack, 중복 ack
        p.timer?.cancel()
        log.info("delivered {}", m.id)
        sendTo(p.from, Status(m.id, "delivered"))
    }

    private suspend fun sendTo(vehicleId: String, out: Outbound) {
        val s = sessions[vehicleId] ?: return
        runCatching { s.send(Frame.Text(json.encodeToString(Outbound.serializer(), out))) }
    }
}
