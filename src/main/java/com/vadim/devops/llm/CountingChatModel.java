package com.vadim.devops.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Wraps ChatModel to:
 * - Record final accumulated token usage (after all tool-calling rounds complete).
 * - Record initial prompt messages for topic breakdown.
 * Per-round call counting is done by LlmCallObservationHandler.
 */
public class CountingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageTracker tracker;

    public CountingChatModel(ChatModel delegate, TokenUsageTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var response = delegate.call(prompt);
        tracker.recordMessages(prompt.getInstructions());
        if (response != null) {
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                tracker.recordFinalUsage(
                        usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                        usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
            }
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
