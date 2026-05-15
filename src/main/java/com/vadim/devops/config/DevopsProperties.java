package com.vadim.devops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devops")
public record DevopsProperties(
        LlmProperties llm,
        KbProperties kb,
        SshProperties ssh,
        MonitoringProperties monitoring,
        TelegramProperties telegram,
        SearchProperties search
) {
    public record LlmProperties(String provider) {
        public boolean isOpenAi() { return "openai".equalsIgnoreCase(provider); }
    }
    public record KbProperties(String path) {}

    public record SshProperties(
            String privateKeyPath,
            int connectTimeoutMs,
            int commandTimeoutMs
    ) {}

    public record MonitoringProperties(
            long cheapCheckIntervalMs,
            long slowCheckIntervalMs,
            int bashTimeoutSeconds,
            int profilingDurationSeconds,
            int profilingCommandTimeoutSeconds
    ) {}

    public record TelegramProperties(
            String token,
            String operatorChatId
    ) {
        public boolean isEnabled() {
            return token != null && !token.isBlank();
        }
    }

    public record SearchProperties(String tavilyApiKey) {
        public boolean isEnabled() {
            return tavilyApiKey != null && !tavilyApiKey.isBlank();
        }
    }
}
