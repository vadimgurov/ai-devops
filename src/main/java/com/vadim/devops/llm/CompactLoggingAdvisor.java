package com.vadim.devops.llm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
public class CompactLoggingAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        var msgs = req.prompt().getInstructions();
        gray("→ LLM [%d msgs]".formatted(msgs.size()));

        var resp = chain.nextCall(req);

        var chatResp = resp.chatResponse();
        if (chatResp != null && chatResp.getResult() != null) {
            var output = chatResp.getResult().getOutput();
            var toolCalls = output.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    gray("← tool_call: %s(%s)".formatted(tc.name(), trunc(tc.arguments(), 150)));
                }
            } else {
                gray("← LLM: %s".formatted(trunc(output.getText(), 150)));
            }
        }

        return resp;
    }

    @Override public String getName() { return "CompactLogging"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    private static String trunc(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactLoggingAdvisor.class);

    private static void gray(String msg) {
        log.debug("{}", msg);
    }
}
