package com.vadim.devops.telegram;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ProgressTracker {

    record State(String chatId, int messageId) {}
    public record Snapshot(String chatId, int messageId) {}

    private final ThreadLocal<State> current = new ThreadLocal<>();
    private final ThreadLocal<Long> lastEditMs = ThreadLocal.withInitial(() -> 0L);

    public void start(String chatId, int messageId) {
        current.set(new State(chatId, messageId));
        lastEditMs.set(0L);
    }

    public Optional<State> get() {
        return Optional.ofNullable(current.get());
    }

    public Optional<Snapshot> snapshot() {
        return get().map(state -> new Snapshot(state.chatId(), state.messageId()));
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) return;
        current.set(new State(snapshot.chatId(), snapshot.messageId()));
        lastEditMs.set(0L);
    }

    public void clear() {
        current.remove();
        lastEditMs.remove();
    }

    public boolean tryAcquireSlot() {
        var now = System.currentTimeMillis();
        if (now - lastEditMs.get() >= 5_000L) {
            lastEditMs.set(now);
            return true;
        }
        return false;
    }
}
