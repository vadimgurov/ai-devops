package com.vadim.devops.telegram;

import com.vadim.devops.llm.TokenUsageTracker;
import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.monitoring.InvestigationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ProgressService {

    private final ProgressTracker tracker;
    private final Optional<TelegramNotifier> notifier;
    private final InvestigationContext investigationContext;
    private final TokenUsageTracker tokenUsageTracker;

    public ProgressService(ProgressTracker tracker, Optional<TelegramNotifier> notifier,
                           InvestigationContext investigationContext,
                           TokenUsageTracker tokenUsageTracker) {
        this.tracker = tracker;
        this.notifier = notifier;
        this.investigationContext = investigationContext;
        this.tokenUsageTracker = tokenUsageTracker;
    }

    public void update(String statusLine) {
        if (notifier.isEmpty()) return;
        var prefix = investigationContext.get()
                .map(incident -> "⏳ Думаю об инциденте " + IncidentFormatter.htmlRef(incident))
                .orElse("⏳ Думаю...");
        var stats = tokenUsageTracker.getStats();
        final var tokenLine = buildTokenLine(stats);
        tracker.get()
                .filter(s -> tracker.tryAcquireSlot())
                .ifPresent(s -> notifier.get().editMessage(
                        s.chatId(), s.messageId(), prefix + tokenLine + "\n\n" + statusLine));
    }

    private static String buildTokenLine(TokenUsageTracker.Stats stats) {
        if (stats.calls() == 0) return "";
        var line = "\n🔢 Вызовов LLM: %d".formatted(stats.calls());
        if (stats.toolCalls() > 0) line += " | Тулов: %d".formatted(stats.toolCalls());
        return line + " | Токены: %,d".formatted(stats.totalTokens());
    }

    public void forceUpdate(String statusLine) {
        if (notifier.isEmpty()) return;
        tracker.get().ifPresent(s -> notifier.get().editMessage(
                s.chatId(), s.messageId(), statusLine));
    }
}
