package com.vadim.devops.monitoring;

import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.llm.LlmAgent;
import com.vadim.devops.llm.TokenUsageTracker;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentEvent;
import com.vadim.devops.telegram.ProgressTracker;
import com.vadim.devops.telegram.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentManagerTest {

    @Mock KnowledgeBaseService kb;
    @Mock InventoryLoader inventory;
    @Mock TelegramNotifier telegramNotifier;
    @Mock ObjectProvider<LlmAgent> llmAgent;
    @Mock ObjectProvider<ProfilingService> profilingService;
    @Mock ProgressTracker progressTracker;
    @Mock InvestigationContext investigationContext;
    @Mock TokenUsageTracker tokenUsageTracker;

    IncidentManager manager;

    @BeforeEach
    void setUp() {
        manager = new IncidentManager(kb, inventory, Optional.of(telegramNotifier), llmAgent,
                profilingService, progressTracker, investigationContext, tokenUsageTracker,
                3_600_000L, 604_800_000L);
        when(inventory.findHost(anyString())).thenReturn(Optional.of(
                new Host("h1", "h1", "test", "1.2.3.4", "h1@example.com", null, null, null, null)));
        when(kb.findOpenIncidents()).thenReturn(List.of());
    }

    @Test
    void onAnomaly_recurrenceOfResolvedIncident_notifiesOnFirstOccurrence() {
        var prev = resolvedIncident("java.lang.NullPointerException: boom", Instant.now().minusSeconds(7200));
        when(kb.findSimilarIncidents(eq("svc1"), anyString())).thenReturn(List.of(prev));

        manager.onAnomaly(new Anomaly(Anomaly.Type.EXCEPTION_BURST, "h1", "svc1",
                "Исключения в логах: java.lang.NullPointerException: boom"));

        verify(telegramNotifier).sendMessage(contains("🔁 Повтор"));
    }

    @Test
    void onAnomaly_recurrenceWithinCooldown_doesNotSpamChat() {
        var notifiedRecently = new IncidentEvent(Instant.now().minusSeconds(60), "recurrence",
                Map.of("details", "x", "notified", true));
        var prev = resolvedIncident("java.lang.NullPointerException: boom", Instant.now().minusSeconds(7200))
                .addEvent(notifiedRecently);
        when(kb.findSimilarIncidents(eq("svc1"), anyString())).thenReturn(List.of(prev));

        manager.onAnomaly(new Anomaly(Anomaly.Type.EXCEPTION_BURST, "h1", "svc1",
                "Исключения в логах: java.lang.NullPointerException: boom"));

        verify(telegramNotifier, never()).sendMessage(anyString());
        // still recorded so recurrence count / dedup keeps working
        verify(kb).saveIncident(argThat(i -> i.events().size() == 2));
    }

    @Test
    void onAnomaly_recurrenceAfterCooldownExpires_notifiesAgain() {
        var notifiedLongAgo = new IncidentEvent(Instant.now().minusSeconds(7200), "recurrence",
                Map.of("details", "x", "notified", true));
        var prev = resolvedIncident("java.lang.NullPointerException: boom", Instant.now().minusSeconds(10_000))
                .addEvent(notifiedLongAgo);
        when(kb.findSimilarIncidents(eq("svc1"), anyString())).thenReturn(List.of(prev));

        manager.onAnomaly(new Anomaly(Anomaly.Type.EXCEPTION_BURST, "h1", "svc1",
                "Исключения в логах: java.lang.NullPointerException: boom"));

        verify(telegramNotifier).sendMessage(contains("🔁 Повтор"));
    }

    @Test
    void onAnomaly_metricHighAfterInvestigatedSpike_recordsRecurrenceInsteadOfNewIncident() {
        var prev = resolvedMetricIncident("cpu=100.0 (порог 85)", Instant.now().minusSeconds(3600),
                "Часовая синхронизация Tilda→VK грузит единственное ядро");
        when(kb.findLastResolvedIncident("h1", "cpu")).thenReturn(Optional.of(prev));

        manager.onAnomaly(new Anomaly(Anomaly.Type.METRIC_HIGH, "h1", "cpu", "cpu=100.0 (порог 85)"));

        verify(kb).saveIncident(argThat(i -> i.id().equals("inc-cpu")
                && i.status() == Incident.Status.RESOLVED
                && i.events().stream().anyMatch(e -> "recurrence".equals(e.eventType()))));
        verify(telegramNotifier).sendMessage(contains("🔁 Повтор"));
        verify(telegramNotifier, never()).sendIncidentAlert(any());
        verify(profilingService, never()).getIfAvailable();
    }

    @Test
    void onAnomaly_metricHighWithoutInvestigatedPredecessor_opensNewIncident() {
        // закрыт автовосстановлением до расследования — причина неизвестна, повтором не считаем
        var notInvestigated = resolvedMetricIncident("cpu=100.0 (порог 85)",
                Instant.now().minusSeconds(3600), null);
        when(kb.findLastResolvedIncident("h1", "cpu")).thenReturn(Optional.of(notInvestigated));

        manager.onAnomaly(new Anomaly(Anomaly.Type.METRIC_HIGH, "h1", "cpu", "cpu=100.0 (порог 85)"));

        verify(telegramNotifier).sendIncidentAlert(argThat(i -> i.status() == Incident.Status.PROFILING));
    }

    @Test
    void onAnomaly_metricHighAfterLookbackExpired_investigatesAgain() {
        var stale = resolvedMetricIncident("cpu=100.0 (порог 85)",
                Instant.now().minus(Duration.ofDays(8)), "старая причина");
        when(kb.findLastResolvedIncident("h1", "cpu")).thenReturn(Optional.of(stale));

        manager.onAnomaly(new Anomaly(Anomaly.Type.METRIC_HIGH, "h1", "cpu", "cpu=100.0 (порог 85)"));

        verify(telegramNotifier).sendIncidentAlert(argThat(i -> i.status() == Incident.Status.PROFILING));
    }

    @Test
    void onAnomaly_healthFail_isNotDedupedByMetricRecurrence() {
        manager.onAnomaly(new Anomaly(Anomaly.Type.HEALTH_FAIL, "h1", "svc1", "Health check не прошёл"));

        verify(kb, never()).findLastResolvedIncident(anyString(), anyString());
        verify(telegramNotifier).sendIncidentAlert(argThat(i -> i.status() == Incident.Status.OPEN));
    }

    private static Incident resolvedMetricIncident(String summary, Instant startedAt, String hypothesis) {
        return new Incident("inc-cpu", "h1", "cpu", Incident.Status.RESOLVED, Incident.Severity.MEDIUM,
                startedAt, Instant.now(), summary, hypothesis, hypothesis == null ? null : 1.0, List.of());
    }

    private static Incident resolvedIncident(String summary, Instant startedAt) {
        return new Incident("inc-1", "h1", "svc1", Incident.Status.RESOLVED, Incident.Severity.HIGH,
                startedAt, Instant.now(), summary, "some cause", 1.0, List.of());
    }
}
