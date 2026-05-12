package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashResult;
import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.telegram.ApprovalService;
import com.vadim.devops.telegram.ProgressTracker;
import com.vadim.devops.telegram.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilingServiceTest {

    @Mock KnowledgeBaseService kb;
    @Mock InventoryLoader inventory;
    @Mock BashRunner runner;
    @Mock ApprovalService approvalService;
    @Mock TelegramNotifier telegramNotifier;
    @Mock ProgressTracker progressTracker;

    ProfilingService service;

    @BeforeEach
    void setUp() {
        var props = new DevopsProperties(
                new DevopsProperties.KbProperties("./kb"),
                new DevopsProperties.SshProperties(null, 1000, 1000),
                new DevopsProperties.MonitoringProperties(1000, 1000, 30, 60, 90),
                new DevopsProperties.TelegramProperties("token", "chat"),
                new DevopsProperties.SearchProperties(null));
        service = new ProfilingService(
                kb, inventory, runner, approvalService, Optional.of(telegramNotifier), progressTracker, props);
    }

    @Test
    void profileAndOpen_reportsSuccessfulAndFailedServices() throws Exception {
        var okSvc = new ServiceConfig("go-ok", "go-ok", "h1", "go", null, null,
                null, null, null, null, null, null, null);
        var failSvc = new ServiceConfig("go-fail", "go-fail", "h1", "go", null, null,
                null, null, null, null, null, null, null);
        var host = new Host("h1", "host1", "prod", "1.2.3.4", "host1@example.com",
                null, List.of(okSvc, failSvc), null, null);
        var incident = new Incident("inc-1", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-1")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok(""))               // ps aux
                .thenReturn(ok("goroutine-a"))    // go-ok goroutines
                .thenReturn(ok(""))               // go-ok heap
                .thenReturn(ok(""))               // go-fail goroutines
                .thenReturn(ok(""));              // go-fail heap

        invokeProfileAndOpen(incident);

        var messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramNotifier).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .contains("Профайл CPU собран для <code>inc-1</code> (хост: <code>h1</code>, сервис: <code>cpu</code>, проблема: CPU high) — начинаю расследование")
                .contains("✅ Собрано: <code>go-ok</code> (go)")
                .contains("⚠️ Не удалось собрать: <code>go-fail</code> (go) — pprof endpoints returned no data");
        verify(kb).saveIncident(any(Incident.class));
    }

    @Test
    void profileAndOpen_profilesPrimaryTargetAndOtherHostServices() throws Exception {
        var mysqlSvc = new ServiceConfig("mysql-test", "mysql-test", "h1", "mysql", null, null,
                null, null, null, null, null, null, null);
        var javaSvc = new ServiceConfig("crm-java-test", "crm-java-test", "h1", "java", "crm.service", null,
                null, null, null, null, null, null, null);
        var host = new Host("h1", "host1", "prod", "1.2.3.4", "host1@example.com",
                null, List.of(mysqlSvc, javaSvc), null, null);
        var incident = new Incident("inc-2", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-2")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok("ubuntu 1000 99.0 0.1 0 0 ? S 00:00 0:00 mysqld")) // ps aux => mysql primary
                .thenReturn(ok("Id\tUser\tHost\tdb\tCommand\tTime\tState\tInfo"))   // mysql processlist
                .thenReturn(ok("INNODB STATUS"))                                      // mysql innodb
                .thenReturn(ok("1234"))                                               // java main pid
                .thenReturn(ok("/usr/local/bin/asprof"))                              // which asprof
                .thenReturn(ok("Thread dump line"));                                  // jcmd/jstack
        when(runner.run(anyString(), anyInt()))
                .thenReturn(ok("12.34% 12.34% 123456 com.example.Foo"));              // asprof flat output

        invokeProfileAndOpen(incident);

        var messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramNotifier).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .contains("✅ Собрано: <code>mysql-test</code> (mysql), <code>crm-java-test</code> (java)");
        verify(runner, times(6)).run(anyString());
        verify(runner).run(anyString(), anyInt());
    }

    @Test
    void profileAndOpen_reportsProfilerTimeoutReasonForJava() throws Exception {
        var javaSvc = new ServiceConfig("crm-java-test", "crm-java-test", "h1", "java", "crm.service", null,
                null, null, null, null, null, null, null);
        var host = new Host("h1", "host1", "prod", "1.2.3.4", "host1@example.com",
                null, List.of(javaSvc), null, null);
        var incident = new Incident("inc-3", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-3")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok("java 1234 99.0 0.1 0 0 ? S 00:00 0:00 java -jar crm.jar"))
                .thenReturn(ok("1234"))
                .thenReturn(ok("/usr/local/bin/asprof"));
        when(runner.run(anyString(), anyInt()))
                .thenReturn(new BashResult(-1, "", "timeout"));

        invokeProfileAndOpen(incident);

        verify(kb).saveIncident(any(Incident.class));
    }

    @Test
    void profileAndOpen_reportsAsyncProfilerStderrInsteadOfEmptyOutput() throws Exception {
        var javaSvc = new ServiceConfig("negotiator", "negotiator", "h1", "java", null, "deploy-negotiator-1",
                null, null, null, null, null, null, null);
        var host = new Host("h1", "baraban", "prod", "1.2.3.4", "baraban@example.com",
                null, List.of(javaSvc), null, null);
        var incident = new Incident("inc-5", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-5")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok("java 1234 99.0 0.1 0 0 ? S 00:00 0:00 java -jar app.jar"))
                .thenReturn(ok("4242"))
                .thenReturn(ok("/usr/local/bin/asprof"));
        when(runner.run(anyString(), anyInt()))
                .thenReturn(new BashResult(1, "", "Failed to change credentials to match the target process: Operation not permitted"));

        invokeProfileAndOpen(incident);

        var messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramNotifier).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .contains("⚠️ Профайл CPU не собран для <code>inc-5</code> (хост: <code>h1</code>, сервис: <code>cpu</code>, проблема: CPU high)")
                .contains("async-profiler failed: Failed to change credentials to match the target process: Operation not permitted");
    }

    @Test
    void profileAndOpen_usesUserLocalPySpyWithoutRepeatedApproval() throws Exception {
        var pythonSvc = new ServiceConfig("bloggers", "bloggers", "h1", "python", null, null,
                null, null, null, null, null, null, null);
        var host = new Host("h1", "host1", "prod", "1.2.3.4", "host1@example.com",
                null, List.of(pythonSvc), null, null);
        var incident = new Incident("inc-4", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-4")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok("python3 1234 99.0 0.1 0 0 ? S 00:00 0:00 python app.py")) // ps aux
                .thenReturn(ok("1234"))                                                     // pid
                .thenReturn(ok("/home/ubuntu/.local/bin/py-spy\n"))                         // resolvePySpyPath
                .thenReturn(ok("Thread 1\nThread 2"));                                      // py-spy dump

        invokeProfileAndOpen(incident);

        verify(approvalService, never()).requestApproval(any(), anyString(), anyBoolean());
        var messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramNotifier).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .contains("✅ Собрано: <code>bloggers</code> (python)");
    }

    @Test
    void profileAndOpen_reportsPySpyStderrInsteadOfEmptyOutput() throws Exception {
        var pythonSvc = new ServiceConfig("parser", "parser", "h1", "python", null, "deploy-parser-1",
                null, null, null, null, null, null, null);
        var host = new Host("h1", "baraban", "prod", "1.2.3.4", "baraban@example.com",
                null, List.of(pythonSvc), null, null);
        var incident = new Incident("inc-6", "h1", "cpu", Incident.Status.PROFILING,
                Incident.Severity.MEDIUM, Instant.now(), null, "CPU high", null, null, List.of());

        when(inventory.findHost("h1")).thenReturn(Optional.of(host));
        when(telegramNotifier.getOperatorChatId()).thenReturn("");
        when(kb.loadIncident("inc-6")).thenReturn(Optional.of(incident));
        when(runner.run(anyString()))
                .thenReturn(ok("python3 1234 99.0 0.1 0 0 ? S 00:00 0:00 python app.py"))
                .thenReturn(ok("31337"))
                .thenReturn(ok("/home/ubuntu/.local/bin/py-spy\n"))
                .thenReturn(new BashResult(1, "", "Error: Failed to get process executable name. Check that the process is running."));

        invokeProfileAndOpen(incident);

        var messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramNotifier).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .contains("⚠️ Профайл CPU не собран для <code>inc-6</code> (хост: <code>h1</code>, сервис: <code>cpu</code>, проблема: CPU high)")
                .contains("py-spy failed: Error: Failed to get process executable name. Check that the process is running.");
    }

    private void invokeProfileAndOpen(Incident incident) throws Exception {
        Method method = ProfilingService.class.getDeclaredMethod("profileAndOpen", Incident.class);
        method.setAccessible(true);
        method.invoke(service, incident);
    }

    private static BashResult ok(String stdout) {
        return new BashResult(0, stdout, "");
    }
}
