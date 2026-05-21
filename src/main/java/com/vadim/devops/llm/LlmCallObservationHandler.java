package com.vadim.devops.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Counts each individual LLM API round (including tool-calling intermediate rounds)
 * by hooking into Spring AI's per-internalCall() Micrometer observation.
 */
public class LlmCallObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private final TokenUsageTracker tracker;

    public LlmCallObservationHandler(TokenUsageTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        int toolCallCount = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        var response = context.getResponse();
        if (response != null) {
            if (response.getResult() != null) {
                var toolCalls = response.getResult().getOutput().getToolCalls();
                if (toolCalls != null) toolCallCount = toolCalls.size();
            }
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            }
        }
        tracker.recordLlmRound(toolCallCount, promptTokens, completionTokens);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }
}
