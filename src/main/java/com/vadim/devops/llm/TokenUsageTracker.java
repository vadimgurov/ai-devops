package com.vadim.devops.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TokenUsageTracker {

    public record Stats(int calls, int promptTokens, int completionTokens, Map<String, Integer> topicChars) {
        public int totalTokens() { return promptTokens + completionTokens; }

        public String topTopicsSummary() {
            if (topicChars.isEmpty()) return "";
            return topicChars.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> "%s (~%,d tok)".formatted(e.getKey(), e.getValue() / 4))
                    .collect(Collectors.joining(", "));
        }
    }

    private static class Acc {
        int calls, promptTokens, completionTokens, seenMessages;
        final Map<String, Integer> topicChars = new LinkedHashMap<>();
    }

    // Not ThreadLocal: Spring AI tool-calling loop may switch threads between rounds
    private Acc current;

    public synchronized void start() { current = new Acc(); }

    public synchronized void record(int prompt, int completion, List<Message> messages) {
        if (current == null) return;
        current.calls++;
        current.promptTokens += prompt;
        current.completionTokens += completion;
        for (int i = current.seenMessages; i < messages.size(); i++) {
            var msg = messages.get(i);
            var text = msg.getText();
            if (text != null && !text.isBlank()) {
                current.topicChars.merge(topic(msg.getMessageType(), text), text.length(), Integer::sum);
            }
        }
        current.seenMessages = messages.size();
    }

    public synchronized Stats getStats() {
        return current == null ? new Stats(0, 0, 0, Map.of())
                : new Stats(current.calls, current.promptTokens, current.completionTokens,
                        Map.copyOf(current.topicChars));
    }

    public synchronized void clear() { current = null; }

    private static String topic(MessageType type, String text) {
        return switch (type) {
            case SYSTEM -> "системный промпт";
            case ASSISTANT -> "ответы LLM";
            case TOOL -> detectToolTopic(text);
            default -> text.startsWith("Инцидент") ? "задача/вопрос" : "история диалога";
        };
    }

    private static String detectToolTopic(String text) {
        var lines = text.lines().toList();
        if (lines.size() < 3) return "ответы инструментов";
        double total = lines.size();
        long logLines = lines.stream()
                .filter(l -> l.matches(".*\\b(ERROR|WARN|INFO|DEBUG|FATAL)\\b.*")
                        || l.matches(".*\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*")
                        || l.contains("Exception") || l.contains("Traceback"))
                .count();
        long sourceLines = lines.stream()
                .filter(l -> l.matches(".*(\\bclass\\b|\\binterface\\b|\\bvoid\\b|\\bpublic\\b|\\bimport\\b|\\bdef \\b|\\bfn \\b).*"))
                .count();
        long statsLines = lines.stream()
                .filter(l -> l.matches(".*\\b(PID|RSS|VSZ|BLOCKED|RUNNABLE|WAITING)\\b.*")
                        || l.matches(".*java\\s+\\d+.*"))
                .count();
        if (logLines / total > 0.25) return "логи";
        if (sourceLines / total > 0.15) return "исходники";
        if (statsLines / total > 0.1) return "статистика процессов";
        return "ответы инструментов";
    }
}
