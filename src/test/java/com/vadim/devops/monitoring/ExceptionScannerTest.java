package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashResult;
import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.ServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExceptionScannerTest {

    @Mock InventoryLoader inventory;
    @Mock BashRunner runner;
    @Mock IncidentManager incidentManager;

    ExceptionScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ExceptionScanner(inventory, runner, incidentManager);
    }

    // ── command building ──────────────────────────────────────────────────────

    @Test
    void scan_usesJournalctlForSystemdService() {
        var svc = serviceWithUnit("app.service");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString())).thenReturn(ok(""));

        scanner.scan();

        var cmd = captureCmd();
        assertThat(cmd).contains("journalctl -u app.service --since");
        assertThat(cmd).contains("ssh host1@example.com");
        assertThat(cmd).contains("--since '\"'\"'");
        assertThat(cmd).contains("grep -E '\"'\"'(Exception|Traceback|FATAL|PANIC|panic:)'\"'\"'");
    }

    @Test
    void scan_usesDockerLogsForContainer() {
        var svc = serviceWithContainer("my-container");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString())).thenReturn(ok(""));

        scanner.scan();

        var cmd = captureCmd();
        assertThat(cmd).contains("docker logs my-container --since");
        assertThat(cmd).contains("ssh host1@example.com");
        assertThat(cmd).contains("'\"'\"'(Exception|Traceback|FATAL|PANIC|panic:)'\"'\"'");
    }

    @Test
    void scan_skipsServiceWithNoSystemdAndNoContainer() {
        var svc = serviceNoSource();
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));

        scanner.scan();

        verifyNoInteractions(runner, incidentManager);
    }

    // ── anomaly detection ─────────────────────────────────────────────────────

    @Test
    void scan_opensAnomalyWhenExceptionFound() {
        var svc = serviceWithContainer("app");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString())).thenReturn(ok("java.lang.NullPointerException: null at com.example.Foo"));

        scanner.scan();

        var captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(incidentManager).onAnomaly(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(Anomaly.Type.EXCEPTION_BURST);
        assertThat(captor.getValue().hostId()).isEqualTo("h1");
        assertThat(captor.getValue().serviceId()).isEqualTo("svc1");
        assertThat(captor.getValue().details()).contains("NullPointerException");
    }

    @Test
    void scan_noAnomalyWhenLogsEmpty() {
        var svc = serviceWithContainer("app");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString())).thenReturn(ok(""));

        scanner.scan();

        verifyNoInteractions(incidentManager);
    }

    @Test
    void scan_deduplicatesSameExceptionOnNextCycle() {
        var svc = serviceWithContainer("app");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString())).thenReturn(ok("java.lang.NullPointerException: null"));

        scanner.scan();
        scanner.scan(); // same exception

        verify(incidentManager, times(1)).onAnomaly(any());
    }

    @Test
    void scan_opensNewAnomalyForDifferentException() {
        var svc = serviceWithContainer("app");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        when(runner.run(anyString()))
                .thenReturn(ok("java.lang.NullPointerException: null"))
                .thenReturn(ok("java.lang.OutOfMemoryError: Java heap space"));

        scanner.scan();
        scanner.scan();

        verify(incidentManager, times(2)).onAnomaly(any());
    }

    @Test
    void scan_fingerprintTruncatesLongLine() {
        var svc = serviceWithContainer("app");
        when(inventory.allHosts()).thenReturn(List.of(host("h1", "host1@example.com", svc)));
        var longLine = "java.lang.Exception: " + "x".repeat(200);
        when(runner.run(anyString())).thenReturn(ok(longLine));

        scanner.scan();

        var captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(incidentManager).onAnomaly(captor.capture());
        assertThat(captor.getValue().details()).hasSizeLessThanOrEqualTo(
                "Исключения в логах: ".length() + 120);
    }

    @Test
    void shellQuote_escapesSingleQuotesForBash() {
        assertThat(ExceptionScanner.shellQuote("a'b'c"))
                .isEqualTo("'a'\"'\"'b'\"'\"'c'");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String captureCmd() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(captor.capture());
        return captor.getValue();
    }

    private static Host host(String id, String sshTarget, ServiceConfig svc) {
        return new Host(id, id, "test", "1.2.3.4", sshTarget, null, List.of(svc), null, null);
    }

    private static ServiceConfig serviceWithUnit(String unit) {
        return new ServiceConfig("svc1", "svc1", "h1", "java", unit, null,
                null, null, null, null, null, null, null);
    }

    private static ServiceConfig serviceWithContainer(String container) {
        return new ServiceConfig("svc1", "svc1", "h1", "python", null, container,
                null, null, null, null, null, null, null);
    }

    private static ServiceConfig serviceNoSource() {
        return new ServiceConfig("svc1", "svc1", "h1", "java", null, null,
                null, null, null, null, null, null, null);
    }

    private static BashResult ok(String stdout) {
        return new BashResult(0, stdout, "");
    }
}
