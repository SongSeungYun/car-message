package carmessage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// docs/protocol.md 와 1:1. 필드를 바꾸면 문서도 같이 바꾼다.

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** 앱 → 서버 */
@Serializable
sealed interface Inbound

@Serializable
@SerialName("send")
data class Send(val id: String, val code: String, val to: String? = null, val replyTo: String? = null) : Inbound

@Serializable
@SerialName("ack")
data class Ack(val id: String) : Inbound

/** 서버 → 앱 */
@Serializable
sealed interface Outbound

@Serializable
@SerialName("msg")
data class Msg(val id: String, val from: String, val code: String, val replyTo: String? = null, val sentAt: Long) : Outbound

@Serializable
@SerialName("status")
data class Status(val id: String, val state: String, val reason: String? = null) : Outbound

@Serializable
@SerialName("error")
data class ErrorMsg(val id: String? = null, val reason: String) : Outbound
