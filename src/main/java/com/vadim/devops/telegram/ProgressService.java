package com.vadim.devops.telegram;

import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.monitoring.InvestigationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ProgressService {

    private final ProgressTracker tracker;
    private final Optional<TelegramNotifier> notifier;
    private final InvestigationContext investigationContext;

    public ProgressService(ProgressTracker tracker, Optional<TelegramNotifier> notifier,
                           InvestigationContext investigationContext) {
        this.tracker = tracker;
        this.notifier = notifier;
        this.investigationContext = investigationContext;
    }

    public void update(String statusLine) {
        if (notifier.isEmpty()) return;
        var prefix = investigationContext.get()
                .map(incident -> "⏳ Думаю об инциденте " + IncidentFormatter.htmlRef(incident))
                .orElse("⏳ Думаю...");
        tracker.get()
                .filter(s -> tracker.tryAcquireSlot())
                .ifPresent(s -> notifier.get().editMessage(
                        s.chatId(), s.messageId(), prefix + "\n\n" + statusLine));
    }

    public void forceUpdate(String statusLine) {
        if (notifier.isEmpty()) return;
        tracker.get().ifPresent(s -> notifier.get().editMessage(
                s.chatId(), s.messageId(), statusLine));
    }
}
