package carmessage

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val VEHICLE_ID = Regex("^[A-Za-z0-9_-]{1,32}$")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module(ackTimeout: Duration = 3.seconds) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = 64 * 1024L // 넘으면 연결을 끊는다. 공개 서버라 메모리 보호
    }
    val relay = Relay(this, ackTimeout)

    routing {
        webSocket("/ws/{vehicleId}") {
            val id = call.parameters["vehicleId"].orEmpty()
            if (!VEHICLE_ID.matches(id) || id == BROADCAST) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad vehicleId"))
            }
            relay.connect(id, this)
        }
    }
}
