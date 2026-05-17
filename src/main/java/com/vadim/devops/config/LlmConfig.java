package com.vadim.devops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vadim.devops.llm.CountingChatModel;
import com.vadim.devops.llm.TokenUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    ChatClient.Builder chatClientBuilder(DeepSeekChatModel deepSeekChatModel,
                                         OpenAiChatModel openAiChatModel,
                                         DevopsProperties props,
                                         TokenUsageTracker tokenUsageTracker) {
        var provider = props.llm().provider();
        log.info("LLM provider: {}", provider);
        var base = props.llm().isOpenAi() ? openAiChatModel : deepSeekChatModel;
        return ChatClient.builder(new CountingChatModel(base, tokenUsageTracker));
    }

    @Bean
    RestClientCustomizer deepSeekThinkingDisabledCustomizer(ObjectMapper objectMapper) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (body.length > 0 && request.getURI().getHost() != null
                    && request.getURI().getHost().contains("deepseek")) {
                try {
                    var node = objectMapper.readTree(body);
                    if (node.isObject()) {
                        ((ObjectNode) node).set("thinking",
                                objectMapper.createObjectNode().put("type", "disabled"));
                        body = objectMapper.writeValueAsBytes(node);
                    }
                } catch (Exception ignored) {
                }
            }
            return execution.execute(request, body);
        });
    }
}