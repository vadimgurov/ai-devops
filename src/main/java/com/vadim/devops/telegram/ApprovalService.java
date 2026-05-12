package com.vadim.devops.telegram;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ApprovalService {

    public enum Decision { YES, YES_ALWAYS, NO }

    private final ConcurrentHashMap<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();
    private final Optional<TelegramNotifier> notifier;

    public ApprovalService(Optional<TelegramNotifier> notifier) {
        this.notifier = notifier;
    }

    public CompletableFuture<Decision> requestApproval(String incidentId, String description, boolean canPermanentlyAllow) {
        var id = UUID.randomUUID().toString();
        var future = new CompletableFuture<Decision>();
        future.whenComplete((r, ex) -> pending.remove(id));
        pending.put(id, future);
        notifier.ifPresent(n -> n.sendApprovalRequest(id, description, canPermanentlyAllow));
        return future;
    }

    /** Called from bot callback handler when operator clicks a button. */
    public boolean resolve(String approvalId, Decision decision) {
        var future = pending.remove(approvalId);
        if (future == null) return false;
        future.complete(decision);
        return true;
    }
}
