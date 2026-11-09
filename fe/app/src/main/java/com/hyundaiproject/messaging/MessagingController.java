package com.hyundaiproject.messaging;

import com.hyundaiproject.messaging.MessageCatalog.Page;
import com.hyundaiproject.messaging.MessageCatalog.Preset;
import com.hyundaiproject.messaging.MessageGateway.Cancellation;
import com.hyundaiproject.messaging.MessageGateway.Incoming;
import com.hyundaiproject.messaging.MessageGateway.Outgoing;
import com.hyundaiproject.messaging.MessageGateway.Result;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates independent outgoing and incoming states. Call UI events on the scheduler thread. */
final class MessagingController implements AutoCloseable {
    private static final long SEND_TIMEOUT_MS = 8_000;
    private static final long STATUS_MS = 1_300;
    private static final long CRITICAL_MS = 4_000;
    private static final long NOTICE_MS = 3_000;
    private static final long VOICE_TIMEOUT_MS = 5_000;
    private static final int MAX_WAITING_ALERTS = 8;
    private static final Pattern NUMBERED_COMMAND = Pattern.compile(
            "^(10|[1-9])\\s*번\\s*(?:(?:메시지|메세지)\\s*)?보내\\s*줘[.!]?$"
    );
    private static final Cancellation NONE = () -> {};

    enum Phase { IDLE, CONFIRM, SENDING, SENT, FAILED, RETRY }

    record Screen(Page page, Phase phase, Preset selected, Incoming alert,
                  boolean alternateGlow, String voiceText) {}

    interface Scheduler {
        Cancellation after(long delayMs, Runnable action);
    }

    interface Observer {
        void render(Screen screen);
    }

    private final MessageGateway gateway;
    private final Scheduler scheduler;
    private final Observer observer;
    private final ArrayDeque<Incoming> waitingAlerts = new ArrayDeque<>();
    private final Cancellation subscription;
    private Cancellation phaseTimer = NONE;
    private Cancellation alertTimer = NONE;
    private Cancellation glowTimer = NONE;
    private Cancellation voiceTimer = NONE;
    private Cancellation request = NONE;
    private Page page = Page.MAIN;
    private Phase phase = Phase.IDLE;
    private Preset selected;
    private Incoming alert;
    private Outgoing outgoing;
    private String voiceText;
    private boolean alternateGlow;
    private boolean closed;
    private int attempt;

    MessagingController(MessageGateway gateway, Scheduler scheduler, Observer observer) {
        this.gateway = gateway;
        this.scheduler = scheduler;
        this.observer = observer;
        subscription = gateway.subscribe(incoming -> scheduler.after(0, () -> receive(incoming)));
    }

    Screen screen() {
        return new Screen(page, phase, selected, alert, alternateGlow, voiceText);
    }

    void showPage(Page next) {
        if (phase != Phase.IDLE || alert != null) return;
        clearVoice();
        page = next;
        publish();
    }

    void select(Preset preset) {
        if (phase != Phase.IDLE || alert != null || !MessageCatalog.presets(page).contains(preset)) return;
        clearVoice();
        selected = preset;
        phase = Phase.CONFIRM;
        publish();
    }

    void confirm() {
        if (alert != null || (phase != Phase.CONFIRM && phase != Phase.RETRY)) return;
        clearVoice();
        if (outgoing == null) {
            outgoing = new Outgoing(UUID.randomUUID().toString(), selected.code(), selected.text());
        }
        int currentAttempt = ++attempt;
        phase = Phase.SENDING;
        publish();
        phaseTimer = scheduler.after(SEND_TIMEOUT_MS, () -> resolve(currentAttempt, Result.FAILURE));
        try {
            request = gateway.send(outgoing,
                    result -> scheduler.after(0, () -> resolve(currentAttempt, result)));
        } catch (RuntimeException failure) {
            resolve(currentAttempt, Result.FAILURE);
        }
    }

    private void resolve(int currentAttempt, Result result) {
        if (closed || currentAttempt != attempt || phase != Phase.SENDING) return;
        phaseTimer.cancel();
        phase = result == Result.SUCCESS ? Phase.SENT : Phase.FAILED;
        request.cancel();
        request = NONE;
        publish();
        phaseTimer = scheduler.after(STATUS_MS, result == Result.SUCCESS ? this::resetSend : this::openRetry);
    }

    void openRetry() {
        if (phase != Phase.FAILED) return;
        phaseTimer.cancel();
        clearVoice();
        phase = Phase.RETRY;
        publish();
    }

    boolean back() {
        if (alert != null) {
            dismissAlert();
        } else if (phase == Phase.CONFIRM || phase == Phase.RETRY || phase == Phase.SENT || phase == Phase.FAILED) {
            resetSend();
        } else if (phase == Phase.SENDING) {
            return true;
        } else if (voiceText != null) {
            stopListening();
        } else if (page != Page.MAIN) {
            showPage(Page.MAIN);
        } else {
            return false;
        }
        return true;
    }

    void cancelConfirmation() {
        if (alert == null && (phase == Phase.CONFIRM || phase == Phase.RETRY)) resetSend();
    }

    private void resetSend() {
        phaseTimer.cancel();
        clearVoice();
        selected = null;
        outgoing = null;
        phase = Phase.IDLE;
        publish();
    }

    void startListening() {
        if (alert != null || (phase != Phase.IDLE && phase != Phase.FAILED)) return;
        if (phase == Phase.FAILED) phaseTimer.cancel();
        clearVoice();
        voiceText = "듣는 중…";
        voiceTimer = scheduler.after(VOICE_TIMEOUT_MS, this::stopListening);
        publish();
    }

    void recognize(String transcript) {
        if (voiceText == null || alert != null) return;
        if (phase == Phase.FAILED && transcript.replaceAll("[\\s.!]", "").equals("재전송해줘")) {
            openRetry();
            return;
        }
        if (phase == Phase.IDLE) {
            Matcher match = NUMBERED_COMMAND.matcher(transcript.trim());
            if (match.matches()) {
                int number = Integer.parseInt(match.group(1));
                Page targetPage = page == Page.TURN ? Page.TURN : number > 5 ? Page.ADDITIONAL : Page.MAIN;
                for (Preset preset : MessageCatalog.presets(targetPage)) {
                    if (preset.number() != number) continue;
                    voiceTimer.cancel();
                    page = targetPage;
                    selected = preset;
                    voiceText = transcript;
                    voiceTimer = scheduler.after(600, () -> select(preset));
                    publish();
                    return;
                }
            }
        }
        stopListening();
    }

    void stopListening() {
        if (voiceText == null) return;
        if (phase == Phase.FAILED) {
            openRetry();
        } else {
            clearVoice();
            publish();
        }
    }

    private void clearVoice() {
        voiceTimer.cancel();
        voiceText = null;
        if (phase == Phase.IDLE) selected = null;
    }

    private void receive(Incoming incoming) {
        if (closed || incoming == null || incoming.kind() == null || incoming.text() == null
                || incoming.text().trim().isEmpty()) return;
        if (voiceText != null) stopListening();
        if (alert == null || (incoming.kind() == Incoming.Kind.CRITICAL && alert.kind() == Incoming.Kind.NOTICE)) {
            if (alert != null) queue(alert);
            showAlert(incoming);
        } else {
            queue(incoming);
        }
    }

    private void queue(Incoming incoming) {
        if (waitingAlerts.size() >= MAX_WAITING_ALERTS) {
            if (incoming.kind() == Incoming.Kind.NOTICE) return;
            waitingAlerts.removeLast();
        }
        waitingAlerts.addLast(incoming);
    }

    private void showAlert(Incoming incoming) {
        alertTimer.cancel();
        glowTimer.cancel();
        alert = incoming;
        alternateGlow = false;
        if (incoming != null) {
            boolean critical = incoming.kind() == Incoming.Kind.CRITICAL;
            alertTimer = scheduler.after(critical ? CRITICAL_MS : NOTICE_MS, this::dismissAlert);
            if (critical) {
                glowTimer = scheduler.after(650, () -> {
                    alternateGlow = true;
                    publish();
                });
            }
        }
        publish();
    }

    void dismissAlert() {
        if (alert != null) showAlert(waitingAlerts.pollFirst());
    }

    private void publish() {
        if (!closed) observer.render(screen());
    }

    @Override public void close() {
        closed = true;
        phaseTimer.cancel();
        alertTimer.cancel();
        glowTimer.cancel();
        voiceTimer.cancel();
        request.cancel();
        subscription.cancel();
        waitingAlerts.clear();
    }
}
