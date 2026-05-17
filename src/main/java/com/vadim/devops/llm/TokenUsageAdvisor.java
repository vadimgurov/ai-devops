package com.vadim.devops.llm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
public class TokenUsageAdvisor implements CallAdvisor {

    private final TokenUsageTracker tracker;

    public TokenUsageAdvisor(TokenUsageTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        var msgs = req.prompt().getInstructions();
        var resp = chain.nextCall(req);
        var chatResp = resp.chatResponse();
        if (chatResp != null) {
            var usage = chatResp.getMetadata().getUsage();
            if (usage != null) {
                tracker.record(
                        usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                        usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                        msgs);
            }
        }
        return resp;
    }

    @Override public String getName() { return "TokenUsage"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }
}