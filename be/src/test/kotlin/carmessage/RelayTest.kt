package carmessage

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class RelayTest {

    private fun relayTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(ackTimeout = 300.milliseconds) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun HttpClient.car(id: String) =
        webSocketSession("/ws/$id").also { delay(50) } // 서버가 세션을 등록할 시간

    private suspend fun DefaultClientWebSocketSession.out(text: String) = send(Frame.Text(text))

    private suspend fun DefaultClientWebSocketSession.recv(): JsonObject =
        withTimeout(2000) { json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject }

    // ackTimeout(300ms)보다 짧게 기다려야 TIMEOUT과 섞이지 않는다
    private suspend fun DefaultClientWebSocketSession.nothing() =
        assertNull(withTimeoutOrNull(150) { incoming.receive() })

    private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `정상 - A가 보내면 B가 받고 ack하면 A에 delivered`() = relayTest { client ->
        val a = client.car("A")
        val b = client.car("B")

        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        val msg = b.recv()
        assertEquals("msg", msg.s("type"))
        assertEquals("A", msg.s("from"))
        assertEquals("U_TURN", msg.s("code"))

        b.out("""{"type":"ack","id":"m1"}""")
        val st = a.recv()
        assertEquals("status", st.s("type"))
        assertEquals("delivered", st.s("state"))
    }

    @Test
    fun `받을 차량이 없으면 NO_TARGET`() = relayTest { client ->
        val a = client.car("A")
        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        val st = a.recv()
        assertEquals("failed", st.s("state"))
        assertEquals("NO_TARGET", st.s("reason"))
    }

    @Test
    fun `ack가 없으면 TIMEOUT`() = relayTest { client ->
        val a = client.car("A")
        val b = client.car("B")
        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        b.recv()
        val st = a.recv()
        assertEquals("failed", st.s("state"))
        assertEquals("TIMEOUT", st.s("reason"))
    }

    @Test
    fun `같은 id 재전송 - 대기 중이면 무시, 도착 후면 delivered만 다시`() = relayTest { client ->
        val a = client.car("A")
        val b = client.car("B")
        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        b.recv()
        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        b.nothing()

        b.out("""{"type":"ack","id":"m1"}""")
        assertEquals("delivered", a.recv().s("state"))

        a.out("""{"type":"send","id":"m1","code":"U_TURN"}""")
        assertEquals("delivered", a.recv().s("state"))
        b.nothing()
    }

    @Test
    fun `답장 - B가 replyTo로 A에게`() = relayTest { client ->
        val a = client.car("A")
        val b = client.car("B")
        a.out("""{"type":"send","id":"m1","code":"YIELD_PLEASE"}""")
        b.recv()
        b.out("""{"type":"ack","id":"m1"}""")
        a.recv()

        b.out("""{"type":"send","id":"m2","code":"I_YIELD","to":"A","replyTo":"m1"}""")
        val msg = a.recv()
        assertEquals("I_YIELD", msg.s("code"))
        assertEquals("B", msg.s("from"))
        assertEquals("m1", msg.s("replyTo"))
    }

    @Test
    fun `잘못된 형식은 BAD_FORMAT`() = relayTest { client ->
        val a = client.car("A")
        a.out("""not json""")
        assertEquals("BAD_FORMAT", a.recv().s("reason"))
        a.out("""{"type":"send","id":"m1","code":"lowercase"}""")
        assertEquals("BAD_FORMAT", a.recv().s("reason"))
    }
}
