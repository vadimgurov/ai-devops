package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashResult;
import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.model.TelemetryCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringSchedulerTest {

    @Mock InventoryLoader inventory;
    @Mock BashRunner runner;
    @Mock AnomalyDetector anomalyDetector;
    @Mock IncidentManager incidentManager;

    MonitoringScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MonitoringScheduler(inventory, runner, anomalyDetector, incidentManager);
    }

    @Test
    void healthCheckOnRemoteHostGoesThroughSsh() {
        when(inventory.allHosts()).thenReturn(List.of(host("crm-prod", "ubuntu@crm", service(), null)));
        when(runner.run(anyString())).thenReturn(ok("ok"));

        scheduler.cheapCheck();

        assertThat(captureCmd()).isEqualTo("ssh ubuntu@crm 'curl -fsS http://localhost:8090/health'");
    }

    @Test
    void healthCheckOnLocalHostRunsWithoutSsh() {
        when(inventory.allHosts()).thenReturn(List.of(host("self-host", "null", service(), null)));
        when(runner.run(anyString())).thenReturn(ok("ok"));

        scheduler.cheapCheck();

        assertThat(captureCmd()).isEqualTo("curl -fsS http://localhost:8090/health");
    }

    @Test
    void telemetryOnLocalHostRunsWithoutSsh() {
        var check = new TelemetryCheck("disk", "df / | awk 'NR==2 {print $5}'", 90.0, null);
        when(inventory.allHosts()).thenReturn(List.of(host("self-host", null, null, check)));
        when(runner.run(anyString())).thenReturn(ok("42"));

        scheduler.slowCheck();

        assertThat(captureCmd()).isEqualTo("df / | awk 'NR==2 {print $5}'");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String captureCmd() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(captor.capture());
        return captor.getValue();
    }

    private static ServiceConfig service() {
        return new ServiceConfig("agent", "agent", "self-host", "java", null, null,
                "curl -fsS http://localhost:8090/health", null, null, null, null, 0L, List.of());
    }

    private static Host host(String id, String sshTarget, ServiceConfig svc, TelemetryCheck check) {
        return new Host(id, id, "prod", "127.0.0.1", sshTarget, null,
                svc == null ? List.of() : List.of(svc),
                check == null ? List.of() : List.of(check), List.of());
    }

    private static BashResult ok(String stdout) {
        return new BashResult(0, stdout, "", false);
    }
}
