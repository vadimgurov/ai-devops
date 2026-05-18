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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

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
    RestClientCustomizer llmCallCounterCustomizer(TokenUsageTracker tokenUsageTracker, ObjectMapper objectMapper) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (request.getMethod() != HttpMethod.POST) return execution.execute(request, body);
            var path = request.getURI().getPath();
            if (path == null || !path.contains("/chat/completions")) return execution.execute(request, body);

            var raw = execution.execute(request, body);
            var responseBody = raw.getBody().readAllBytes();
            try {
                var node = objectMapper.readTree(responseBody);
                var usage = node.path("usage");
                if (!usage.isMissingNode()) {
                    tokenUsageTracker.recordCall(usage.path("prompt_tokens").asInt(0),
                            usage.path("completion_tokens").asInt(0));
                }
            } catch (Exception ignored) {}
            return new BufferedClientHttpResponse(raw, responseBody);
        });
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

    private record BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { delegate.close(); }
    }
}