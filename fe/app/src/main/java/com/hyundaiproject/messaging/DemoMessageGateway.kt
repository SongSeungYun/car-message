package com.hyundaiproject.messaging

import android.os.Handler

internal class DemoMessageGateway(private val handler: Handler) : MessageGateway {
    var nextResult = MessageGateway.Result.SUCCESS
    private var receiver: MessageGateway.Receiver? = null

    override fun send(
        outgoing: MessageGateway.Outgoing,
        callback: MessageGateway.SendCallback,
    ): MessageGateway.Cancellation {
        val result = nextResult
        nextResult = MessageGateway.Result.SUCCESS
        val delivery = Runnable { callback.onResult(result) }
        handler.postDelayed(delivery, 900)
        return MessageGateway.Cancellation { handler.removeCallbacks(delivery) }
    }

    override fun subscribe(receiver: MessageGateway.Receiver): MessageGateway.Cancellation {
        this.receiver = receiver
        return MessageGateway.Cancellation { if (this.receiver === receiver) this.receiver = null }
    }

    fun emit(kind: MessageGateway.Incoming.Kind) {
        val incoming = when (kind) {
            MessageGateway.Incoming.Kind.CRITICAL -> MessageGateway.Incoming(kind, "전방 특이사항 발생", null)
            MessageGateway.Incoming.Kind.NOTICE -> MessageGateway.Incoming(kind, "유턴합니다", "**가 5993")
        }
        receiver?.onMessage(incoming)
    }
}
