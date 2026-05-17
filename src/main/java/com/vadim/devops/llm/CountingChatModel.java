package com.vadim.devops.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Wraps a ChatModel to record per-call token usage in TokenUsageTracker.
 * Unlike a ChatClient advisor, this intercepts every individual LLM API call
 * including intermediate tool-calling rounds.
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
        record(prompt.getInstructions(), response);
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

    private void record(List<Message> messages, ChatResponse response) {
        if (response == null) return;
        var usage = response.getMetadata().getUsage();
        if (usage == null) return;
        tracker.record(
                usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                messages);
    }
}
