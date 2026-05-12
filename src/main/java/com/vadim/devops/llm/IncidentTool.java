package com.vadim.devops.llm;

import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.monitoring.IncidentManager;
import com.vadim.devops.telegram.ProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class IncidentTool {

    private static final Logger log = LoggerFactory.getLogger(IncidentTool.class);

    private final IncidentManager incidentManager;
    private final KnowledgeBaseService kb;
    private final ProgressService progressService;

    public IncidentTool(IncidentManager incidentManager, KnowledgeBaseService kb,
                        ProgressService progressService) {
        this.incidentManager = incidentManager;
        this.kb = kb;
        this.progressService = progressService;
    }

    @Tool(description = "Список открытых инцидентов (статус OPEN или INVESTIGATING)")
    public String listOpenIncidents() {
        progressService.update("🚨 Список открытых инцидентов...");
        var incidents = incidentManager.openIncidents();
        if (incidents.isEmpty()) return "Открытых инцидентов нет.";
        return incidents.stream()
                .map(i -> "[%s] %s/%s — %s (%s)".formatted(
                        i.id(), i.hostId(), i.serviceId(), i.summary(), i.severity()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            Найти похожие инциденты из прошлого по сервису и ключевым словам.
            Используй чтобы найти знакомые паттерны и посмотреть как решали раньше.
            """)
    public String searchSimilarIncidents(String serviceId, String keywords) {
        progressService.update("🔍 Ищу похожие инциденты: " + serviceId + " / " + keywords + "...");
        var found = incidentManager.findSimilarIncidents(serviceId, keywords);
        if (found.isEmpty()) return "Похожих инцидентов не найдено.";
        return found.stream()
                .map(this::formatIncidentSummary)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "Загрузить полные детали инцидента: метаданные, события, историю расследования (разговор с LLM).")
    public String getIncident(String incidentId) {
        var incidentOpt = incidentManager.loadIncident(incidentId);
        if (incidentOpt.isEmpty()) return "Инцидент не найден: " + incidentId;

        var incident = incidentOpt.get();
        progressService.update("📄 Загружаю инцидент " + IncidentFormatter.htmlRef(incident) + "...");
        var sb = new StringBuilder(formatIncidentSummary(incident));

        var conversation = kb.loadConversation(incidentId);
        if (!conversation.isEmpty()) {
            sb.append("\n\nИстория расследования:\n");
            conversation.forEach(m ->
                    sb.append("[").append(m.role()).append("] ")
                      .append(m.content(), 0, Math.min(500, m.content().length()))
                      .append("\n"));
        }
        return sb.toString();
    }

    @Tool(description = """
            Обновить гипотезу и confidence инцидента после расследования.
            Вызывай когда определил корневую причину.
            confidence: 0.0–1.0
            """)
    public String updateIncidentHypothesis(String incidentId, String hypothesis, double confidence) {
        incidentManager.updateHypothesis(incidentId, hypothesis, confidence);
        log.info("updateIncidentHypothesis {} → {} ({:.0f}%)", incidentId, hypothesis, confidence * 100);
        return incidentManager.loadIncident(incidentId)
                .map(incident -> "Гипотеза сохранена для " + IncidentFormatter.plainRef(incident))
                .orElse("Гипотеза сохранена для " + incidentId);
    }

    @Tool(description = "Пометить инцидент как решённый с указанием итогового вывода.")
    public String resolveIncident(String incidentId, String resolution) {
        var ok = incidentManager.resolveFromInvestigation(incidentId, resolution);
        log.info("resolveIncident {}", incidentId);
        if (!ok) return "Инцидент не найден: " + incidentId;
        return incidentManager.loadIncident(incidentId)
                .map(incident -> "Инцидент закрыт: " + IncidentFormatter.plainRef(incident))
                .orElse("Инцидент %s закрыт.".formatted(incidentId));
    }

    private String formatIncidentSummary(Incident i) {
        var sb = new StringBuilder();
        sb.append("ID: ").append(i.id()).append("\n");
        sb.append("Хост/Сервис: ").append(i.hostId()).append("/").append(i.serviceId()).append("\n");
        sb.append("Статус: ").append(i.status()).append(" | Severity: ").append(i.severity()).append("\n");
        sb.append("Начало: ").append(i.startedAt()).append("\n");
        if (i.resolvedAt() != null) sb.append("Закрыт: ").append(i.resolvedAt()).append("\n");
        sb.append("Описание: ").append(i.summary()).append("\n");
        if (i.rootCauseHypothesis() != null)
            sb.append("Гипотеза: ").append(i.rootCauseHypothesis())
              .append(" (уверенность: ").append((int)(i.confidence() * 100)).append("%)\n");
        if (i.events() != null && !i.events().isEmpty()) {
            sb.append("События:\n");
            i.events().forEach(e ->
                    sb.append("  ").append(e.ts()).append(" [").append(e.eventType()).append("] ")
                      .append(e.payload()).append("\n"));
        }
        return sb.toString();
    }
}
