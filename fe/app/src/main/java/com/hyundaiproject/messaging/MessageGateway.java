package com.hyundaiproject.messaging;

/** Adapter boundary for the team's server. The callbacks may run on any thread. */
public interface MessageGateway {
    record Outgoing(String requestId, String messageCode, String text) {}

    record Incoming(Kind kind, String text, String maskedSender) {
        public enum Kind { CRITICAL, NOTICE }
    }

    enum Result { SUCCESS, FAILURE }

    interface SendCallback {
        void onResult(Result result);
    }

    interface Receiver {
        void onMessage(Incoming incoming);
    }

    interface Cancellation {
        void cancel();
    }

    /** Use requestId as the server idempotency key, including on a retry. */
    Cancellation send(Outgoing outgoing, SendCallback callback);

    Cancellation subscribe(Receiver receiver);
}
