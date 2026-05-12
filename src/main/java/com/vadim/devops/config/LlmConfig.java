package com.vadim.devops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

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
