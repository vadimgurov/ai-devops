package com.vadim.devops.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Wraps a ChatModel to record message topic breakdown in TokenUsageTracker.
 * Call counting and token counting happen at HTTP level in LlmConfig.
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
