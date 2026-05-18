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
        var response = context.getResponse();
        if (response != null && response.getResult() != null) {
            var toolCalls = response.getResult().getOutput().getToolCalls();
            if (toolCalls != null) toolCallCount = toolCalls.size();
        }
        tracker.recordLlmRound(toolCallCount);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }
}
