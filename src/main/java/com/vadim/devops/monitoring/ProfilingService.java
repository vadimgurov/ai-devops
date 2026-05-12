package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashResult;
import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.model.IncidentEvent;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.telegram.ApprovalService;
import com.vadim.devops.telegram.ProgressTracker;
import com.vadim.devops.telegram.TelegramNotifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ProfilingService {

    private static final Logger log = LoggerFactory.getLogger(ProfilingService.class);
    private static final Set<String> SUPPORTED_RUNTIMES = Set.of(
            "java", "python", "go", "postgresql", "mysql", "mongodb");

    private final KnowledgeBaseService kb;
    private final InventoryLoader inventory;
    private final BashRunner runner;
    private final ApprovalService approvalService;
    private final Optional<TelegramNotifier> telegram;
    private final ProgressTracker progressTracker;
    private final int durationSeconds;
    private final int profilingCommandTimeoutSeconds;

    private final ExecutorService profilingPool = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "profiler"));
    private final ConcurrentHashMap<String, Future<?>> activeProfilers = new ConcurrentHashMap<>();

    private record ProfilingResult(String data, String reason) {
        static ProfilingResult success(String data) { return new ProfilingResult(data, null); }
        static ProfilingResult failure(String reason) { return new ProfilingResult(null, reason); }
        boolean success() { return data != null && !data.isBlank(); }
    }

    private record FailedProfiling(ServiceConfig service, String reason) {}

    public ProfilingService(KnowledgeBaseService kb, InventoryLoader inventory,
                            BashRunner runner, ApprovalService approvalService,
                            Optional<TelegramNotifier> telegram, ProgressTracker progressTracker,
                            DevopsProperties props) {
        this.kb = kb;
        this.inventory = inventory;
        this.runner = runner;
        this.approvalService = approvalService;
        this.telegram = telegram;
        this.progressTracker = progressTracker;
        this.durationSeconds = props.monitoring().profilingDurationSeconds();
        this.profilingCommandTimeoutSeconds = props.monitoring().profilingCommandTimeoutSeconds();
    }

    @PostConstruct
    void init() {
        // Resume any PROFILING incidents that survived a restart
        kb.findOpenIncidents().stream()
                .filter(i -> i.status() == Incident.Status.PROFILING)
                .forEach(i -> {
                    log.info("Возобновляю профайлинг после перезапуска: {}", i.id());
                    profilingPool.submit(() -> profileAndOpen(i));
                });
    }

    /** Called by IncidentManager immediately when a CPU anomaly creates a PROFILING incident. */
    public void submit(Incident incident) {
        var future = profilingPool.submit(() -> {
            try { profileAndOpen(incident); }
            finally { activeProfilers.remove(incident.id()); }
        });
        activeProfilers.put(incident.id(), future);
    }

    /** Stop profiling and transition incident to OPEN immediately. */
    public boolean skipProfiling(String incidentId) {
        return kb.loadIncident(incidentId)
                .filter(i -> i.status() == Incident.Status.PROFILING)
                .map(i -> {
                    transitionToOpen(i, "Пропущено оператором");
                    var f = activeProfilers.remove(incidentId);
                    if (f != null) f.cancel(true);
                    return true;
                })
                .orElse(false);
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private void profileAndOpen(Incident incident) {
        var host = inventory.findHost(incident.hostId()).orElse(null);
        if (host == null) {
            log.warn("Хост не найден для инцидента {}: {}", incident.id(), incident.hostId());
            transitionToOpen(incident, "Хост не найден в инвентаре");
            return;
        }

        var chatId = telegram.map(TelegramNotifier::getOperatorChatId).orElse(null);
        Integer msgId = null;
        if (chatId != null && !chatId.isBlank()) {
            msgId = telegram.get().sendAndGetId(chatId,
                    "⏳ Профайлирую CPU для " + IncidentFormatter.htmlRef(incident));
            if (msgId != null) progressTracker.start(chatId, msgId);
        }
        final var finalChatId = chatId;
        final var finalMsgId = msgId;

        var results = new StringBuilder();
        var profiledServices = new ArrayList<ServiceConfig>();
        var failedServices = new ArrayList<FailedProfiling>();
        try {
            var psOutput = ssh(host, "ps aux --sort=-%cpu | head -10").stdout();
            var target = findTopCpuService(host, psOutput);
            var servicesToProfile = orderServicesForProfiling(host, target);

            for (var svc : servicesToProfile) {
                if (svc.runtime() == null || !SUPPORTED_RUNTIMES.contains(svc.runtime().toLowerCase())) continue;
                if (finalMsgId != null) telegram.get().editMessage(finalChatId, finalMsgId,
                        "⏳ Профайлирую CPU для " + IncidentFormatter.htmlRef(incident)
                                + "\n🔍 " + IncidentFormatter.escapeHtml(svc.id()) + " ("
                                + IncidentFormatter.escapeHtml(svc.runtime()) + ")...");
                var profiling = profile(host, svc);
                if (profiling.success()) {
                    profiledServices.add(svc);
                    results.append("=== ").append(svc.id()).append(" (").append(svc.runtime()).append(") ===\n");
                    results.append(profiling.data()).append("\n\n");
                } else {
                    failedServices.add(new FailedProfiling(svc, profiling.reason()));
                }
            }
        } finally {
            progressTracker.clear();
            if (finalMsgId != null) telegram.ifPresent(t -> t.deleteMessage(finalChatId, finalMsgId));
        }

        var profileData = results.toString().trim();
        if (profileData.isBlank()) {
            var reason = failedServices.isEmpty()
                    ? "Профайл не собран: нет поддерживаемых runtime"
                    : "Профайл не собран: " + formatFailedServiceList(failedServices);
            log.warn("Профайл {}: нет данных профилирования. {}", incident.id(), reason);
            if (!failedServices.isEmpty()) {
                telegram.ifPresent(t -> t.sendMessage(
                        "⚠️ Профайл CPU не собран для " + IncidentFormatter.htmlRef(incident)
                                + "\n" + formatProfilingSummary(List.of(), failedServices)));
            }
            transitionToOpen(incident, reason);
            return;
        }

        // Re-check: incident may have been resolved while profiling was running
        var fresh = kb.loadIncident(incident.id()).orElse(null);
        if (fresh == null || fresh.status() != Incident.Status.PROFILING) {
            log.info("Профайл {}: инцидент уже не в PROFILING ({}), данные отброшены",
                    incident.id(), fresh != null ? fresh.status() : "not found");
            return;
        }

        var updated = fresh
                .addEvent(new IncidentEvent(Instant.now(), "profiling_complete",
                        Map.of("durationSec", durationSeconds, "data", profileData)));
        kb.saveIncident(updated.withStatus(Incident.Status.OPEN));
        log.info("Профайл {} собран, инцидент переведён в OPEN", incident.id());
        telegram.ifPresent(t -> t.sendMessage(
                "✅ Профайл CPU собран для " + IncidentFormatter.htmlRef(incident)
                        + " — начинаю расследование\n" + formatProfilingSummary(profiledServices, failedServices)));
    }

    private void transitionToOpen(Incident incident, String reason) {
        var fresh = kb.loadIncident(incident.id()).orElse(null);
        if (fresh == null || fresh.status() != Incident.Status.PROFILING) return;
        kb.saveIncident(fresh
                .addEvent(new IncidentEvent(Instant.now(), "profiling_skipped", Map.of("reason", reason)))
                .withStatus(Incident.Status.OPEN));
    }

    // ── Per-runtime profiling ─────────────────────────────────────────────────

    private ProfilingResult profile(Host host, ServiceConfig service) {
        try {
            var result = switch (service.runtime().toLowerCase()) {
                case "java"       -> profileJava(host, service);
                case "python"     -> profilePython(host, service);
                case "go"         -> profileGo(host, service);
                case "postgresql" -> profilePostgres(host, service);
                case "mysql"      -> profileMysql(host, service);
                case "mongodb"    -> profileMongo(host, service);
                default           -> ProfilingResult.failure("unsupported runtime: " + service.runtime());
            };
            if (!result.success()) {
                log.warn("Профайлинг {}/{} не собран: {}", host.id(), service.id(), result.reason());
            }
            return result;
        } catch (Exception e) {
            log.error("Профайлинг {}/{} упал", host.id(), service.id(), e);
            return ProfilingResult.failure("exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ProfilingResult profileJava(Host host, ServiceConfig service) {
        var pid = findPid(host, service);
        if (pid == null) return ProfilingResult.failure("PID not found");
        if (!ensureInstalled(host, "asprof", "which asprof",
                "sudo mkdir -p /opt/async-profiler"
                + " && curl -sL https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz"
                + " | sudo tar xz -C /opt/async-profiler --strip-components=1"
                + " && sudo ln -sf /opt/async-profiler/bin/asprof /usr/local/bin/asprof",
                "async-profiler (asprof)")) return ProfilingResult.failure("async-profiler unavailable");
        log.info("Запускаю async-profiler для {}/{}: pid={}, duration={}s, timeout={}s",
                host.id(), service.id(), pid, durationSeconds, profilingCommandTimeoutSeconds);
        // -e itimer uses SIGPROF via setitimer() — works in Docker containers where
        // perf_event_open is blocked by the default seccomp profile.
        var cmd = """
                set -o pipefail
                sudo asprof -d %d -e itimer -o flat %s | sed -n '1,80p'
                """.formatted(durationSeconds, pid).strip();
        var result = ssh(host, cmd, profilingCommandTimeoutSeconds);
        if ("timeout".equals(result.stderr())) {
            return ProfilingResult.failure("async-profiler timed out after " + profilingCommandTimeoutSeconds + "s");
        }
        if (!result.success()) {
            var stderr = result.stderr().trim();
            return ProfilingResult.failure(stderr.isBlank()
                    ? "async-profiler failed with exit " + result.exitCode()
                    : "async-profiler failed: " + stderr);
        }
        if (result.stdout().isBlank()) {
            var stderr = result.stderr().trim();
            return ProfilingResult.failure(stderr.isBlank()
                    ? "async-profiler returned empty output"
                    : "async-profiler returned empty output; stderr: " + stderr);
        }

        var sb = new StringBuilder();
        sb.append("=== Flat profile (samples, %%total, %%self) ===\n");
        sb.append(result.stdout().trim());

        var threadDump = ssh(host, """
                set -o pipefail
                (sudo jcmd %s Thread.print -l 2>/dev/null || sudo jstack %s 2>/dev/null) | sed -n '1,120p'
                """.formatted(pid, pid).strip());
        if (threadDump.success() && !threadDump.stdout().isBlank()) {
            sb.append("\n\n=== Thread dump (first 120 lines) ===\n");
            sb.append(threadDump.stdout().trim());
        }

        return ProfilingResult.success(sb.toString());
    }

    private ProfilingResult profilePython(Host host, ServiceConfig service) {
        var pid = findPid(host, service);
        if (pid == null) return ProfilingResult.failure("PID not found");
        var pySpyPath = ensurePySpy(host);
        if (pySpyPath == null) {
            return ProfilingResult.failure("py-spy unavailable");
        }
        // py-spy dump: one-shot stack trace of all threads (py-spy top --duration does not exist)
        var cmd = "sudo %s dump --pid %s".formatted(pySpyPath, pid);
        var result = ssh(host, cmd);
        if (!result.success()) {
            var stderr = result.stderr().trim();
            return ProfilingResult.failure(stderr.isBlank()
                    ? "py-spy failed with exit " + result.exitCode()
                    : "py-spy failed: " + stderr);
        }
        if (result.stdout().isBlank()) {
            return ProfilingResult.failure("py-spy returned empty output");
        }
        return ProfilingResult.success(result.stdout());
    }

    private String ensurePySpy(Host host) {
        var pySpyPath = resolvePySpyPath(host);
        if (pySpyPath != null) return pySpyPath;
        log.info("py-spy не установлен на {}", host.id());
        try {
            var decision = approvalService.requestApproval(null,
                    "⚙️ Профайлер <b>%s</b> не установлен на <code>%s</code>.\nУстановить?"
                            .formatted("py-spy", host.id()),
                    false).get(5, TimeUnit.MINUTES);
            if (decision != ApprovalService.Decision.YES) {
                log.warn("Установка py-spy на {} отклонена оператором: {}", host.id(), decision);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ожидание подтверждения установки py-spy на {} прервано", host.id());
            return null;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Не удалось дождаться подтверждения установки py-spy на {}: {}", host.id(), e.getMessage());
            return null;
        }
        var install = ssh(host,
                "pip3 install py-spy --break-system-packages 2>/dev/null"
                + " || curl -sL https://github.com/benfred/py-spy/releases/download/v0.3.14/py-spy-0.3.14-x86_64-unknown-linux-musl.tar.gz"
                + " | sudo tar xz -C /usr/local/bin");
        if (!install.success()) {
            log.warn("Установка py-spy на {} не удалась: {}", host.id(), install.stderr());
            return null;
        }
        pySpyPath = resolvePySpyPath(host);
        if (pySpyPath == null) {
            log.warn("py-spy на {} после установки всё ещё не найден в PATH и ~/.local/bin", host.id());
        }
        return pySpyPath;
    }

    private ProfilingResult profileGo(Host host, ServiceConfig service) {
        var port = 6060;
        var goroutines = ssh(host, "curl -s --max-time 5 http://localhost:%d/debug/pprof/goroutine?debug=1 | head -80".formatted(port)).stdout();
        var heap = ssh(host, "curl -s --max-time 5 http://localhost:%d/debug/pprof/heap?debug=1 | head -40".formatted(port)).stdout();
        if (goroutines.isBlank() && heap.isBlank()) {
            return ProfilingResult.failure("pprof endpoints returned no data");
        }
        return ProfilingResult.success("=== Goroutines ===\n" + goroutines + "\n=== Heap ===\n" + heap);
    }

    private ProfilingResult profilePostgres(Host host, ServiceConfig service) {
        var activeQ = ssh(host, """
                sudo -u postgres psql -At -c \
                "SELECT pid, now()-query_start AS dur, state, left(query,120) \
                 FROM pg_stat_activity \
                 WHERE state!='idle' AND query_start < now()-interval '1s' \
                 ORDER BY dur DESC LIMIT 15" 2>/dev/null
                """).stdout();
        var slowQ = ssh(host, """
                sudo -u postgres psql -At -c \
                "SELECT left(query,120), calls, round(total_exec_time/calls) avg_ms, rows/calls avg_rows \
                 FROM pg_stat_statements \
                 ORDER BY total_exec_time DESC LIMIT 15" 2>/dev/null
                """).stdout();
        if (activeQ.isBlank() && slowQ.isBlank()) {
            return ProfilingResult.failure("pg_stat_activity and pg_stat_statements returned no data");
        }
        return ProfilingResult.success("=== Active queries ===\n" + activeQ + "\n=== Slowest queries ===\n" + slowQ);
    }

    private ProfilingResult profileMysql(Host host, ServiceConfig service) {
        var processList = ssh(host, "sudo mysql -e 'SHOW FULL PROCESSLIST' 2>/dev/null").stdout();
        var innodbStatus = ssh(host, "sudo mysql -e 'SHOW ENGINE INNODB STATUS' 2>/dev/null | head -80").stdout();
        if (processList.isBlank()) {
            return ProfilingResult.failure("SHOW FULL PROCESSLIST returned no data");
        }
        return ProfilingResult.success("=== SHOW FULL PROCESSLIST ===\n" + processList + "\n=== INNODB STATUS ===\n" + innodbStatus);
    }

    private ProfilingResult profileMongo(Host host, ServiceConfig service) {
        var container = service.containerName();
        var prefix = container != null
                ? "docker exec %s mongosh --quiet --eval".formatted(container)
                : "mongosh --quiet --eval";
        var cmd = prefix + " \"db.currentOp({active:true}).inprog.filter(op=>op.secs_running>0).map(op=>({ns:op.ns,secs:op.secs_running,op:op.op})).slice(0,15)\" 2>/dev/null";
        var result = ssh(host, cmd).stdout();
        if (result.isBlank()) {
            return ProfilingResult.failure("mongosh currentOp returned no data");
        }
        return ProfilingResult.success("=== MongoDB currentOp ===\n" + result);
    }

    // ── PID detection ─────────────────────────────────────────────────────────

    private String findPid(Host host, ServiceConfig service) {
        if (service.systemdUnit() != null && !service.systemdUnit().isBlank()) {
            var pidResult = ssh(host, "systemctl show %s -p MainPID --value 2>/dev/null".formatted(service.systemdUnit()));
            var pid = pidResult.stdout().trim();
            if (!pid.isBlank() && !pid.equals("0")) return pid;
            log.warn("PID lookup via systemd failed for {}/{}: unit={} stdout='{}' stderr='{}'",
                    host.id(), service.id(), service.systemdUnit(), pid, pidResult.stderr().trim());
        }
        if (service.containerName() != null) {
            var pidResult = ssh(host, "docker inspect %s --format '{{.State.Pid}}' 2>/dev/null".formatted(service.containerName()));
            var pid = pidResult.stdout().trim();
            if (!pid.isBlank() && !pid.equals("0")) return pid;
            log.warn("PID lookup via docker inspect failed for {}/{}: container={} stdout='{}' stderr='{}'",
                    host.id(), service.id(), service.containerName(), pid, pidResult.stderr().trim());
        }
        if (service.runtime() != null && service.runtime().equalsIgnoreCase("java")) {
            log.warn("Пропускаю pgrep fallback для {}/{}: для Java без systemdUnit/containerName он слишком ненадёжен",
                    host.id(), service.id());
            return null;
        }
        var pgrepResult = ssh(host, "pgrep -f %s 2>/dev/null | head -1".formatted(service.id()));
        var pid = pgrepResult.stdout().trim();
        if (!pid.isBlank()) return pid;
        log.warn("Не удалось найти PID для {}/{}: pgrep stdout='{}' stderr='{}'",
                host.id(), service.id(), pid, pgrepResult.stderr().trim());
        return null;
    }

    // ── Tool installation ─────────────────────────────────────────────────────

    private boolean ensureInstalled(Host host, String binary, String checkCmd, String installCmd, String toolLabel) {
        var check = ssh(host, checkCmd);
        if (check.success()) return true;
        log.info("{} не установлен на {}", toolLabel, host.id());
        try {
            var decision = approvalService.requestApproval(null,
                    "⚙️ Профайлер <b>%s</b> не установлен на <code>%s</code>.\nУстановить?".formatted(toolLabel, host.id()),
                    false).get(5, TimeUnit.MINUTES);
            if (decision != ApprovalService.Decision.YES) {
                log.warn("Установка {} на {} отклонена оператором: {}", toolLabel, host.id(), decision);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ожидание подтверждения установки {} на {} прервано", toolLabel, host.id());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Не удалось дождаться подтверждения установки {} на {}: {}", toolLabel, host.id(), e.getMessage());
            return false;
        }
        var result = ssh(host, installCmd);
        if (!result.success()) {
            log.warn("Установка {} на {} не удалась: {}", toolLabel, host.id(), result.stderr());
            return false;
        }
        return true;
    }

    private String resolvePySpyPath(Host host) {
        var result = ssh(host, "command -v py-spy || test -x ~/.local/bin/py-spy && echo ~/.local/bin/py-spy");
        if (!result.success() || result.stdout().isBlank()) return null;
        return result.stdout().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);
    }

    // ── Target detection ─────────────────────────────────────────────────────

    private ServiceConfig findTopCpuService(Host host, String psOutput) {
        if (host.services() == null || psOutput.isBlank()) return null;
        return psOutput.lines()
                .skip(1) // header
                .flatMap(line -> host.services().stream()
                        .filter(svc -> matchesService(line, svc))
                        .findFirst()
                        .stream())
                .findFirst()
                .orElse(null);
    }

    private static boolean matchesService(String processLine, ServiceConfig svc) {
        // ps aux: USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND...
        var parts = processLine.trim().split("\\s+", 11);
        var command = parts.length >= 11 ? parts[10].toLowerCase() : "";
        if (command.isEmpty()) return false;

        if (svc.systemdUnit() != null) {
            // "crm.service" → "crm", matches "java -jar crm-1.0.jar"
            var unit = svc.systemdUnit().toLowerCase().replaceAll("\\.service$", "");
            if (command.contains(unit)) return true;
        }
        if (svc.containerName() != null && command.contains(svc.containerName().toLowerCase())) return true;
        if (command.contains(svc.id().toLowerCase())) return true;

        // Runtime fallback: "java" service matches any "java ..." process
        if (svc.runtime() != null) {
            var rt = svc.runtime().toLowerCase();
            if ("java".equals(rt) && command.startsWith("java")) return true;
            if ("python".equals(rt) && (command.startsWith("python3") || command.startsWith("python "))) return true;
        }
        return false;
    }

    private static List<ServiceConfig> orderServicesForProfiling(Host host, ServiceConfig primaryTarget) {
        var ordered = new LinkedHashMap<String, ServiceConfig>();
        if (primaryTarget != null) {
            ordered.put(primaryTarget.id(), primaryTarget);
        }
        if (host.services() != null) {
            for (var svc : host.services()) {
                ordered.putIfAbsent(svc.id(), svc);
            }
        }
        return List.copyOf(ordered.values());
    }

    private static String formatProfilingSummary(List<ServiceConfig> profiledServices,
                                                 List<FailedProfiling> failedServices) {
        var lines = new ArrayList<String>();
        if (!profiledServices.isEmpty()) {
            lines.add("✅ Собрано: " + formatServiceList(profiledServices));
        }
        if (!failedServices.isEmpty()) {
            lines.add("⚠️ Не удалось собрать: " + formatFailedServiceList(failedServices));
        }
        return String.join("\n", lines);
    }

    private static String formatServiceList(List<ServiceConfig> services) {
        return services.stream()
                .map(svc -> "<code>%s</code> (%s)".formatted(svc.id(), svc.runtime()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String formatFailedServiceList(List<FailedProfiling> services) {
        return services.stream()
                .map(entry -> "<code>%s</code> (%s) — %s".formatted(
                        entry.service().id(), entry.service().runtime(), entry.reason()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    void resumeStuck() {
        kb.findOpenIncidents().stream()
                .filter(i -> i.status() == Incident.Status.PROFILING)
                .forEach(i -> {
                    log.warn("Возобновляю зависший профайлинг: {}", i.id());
                    profilingPool.submit(() -> profileAndOpen(i));
                });
    }

    private BashResult ssh(Host host, String cmd) {
        return runner.run("ssh %s '%s'".formatted(host.sshTarget(), cmd.replace("'", "'\\''")));
    }

    private BashResult ssh(Host host, String cmd, int timeoutSeconds) {
        return runner.run("ssh %s '%s'".formatted(host.sshTarget(), cmd.replace("'", "'\\''")), timeoutSeconds);
    }
}
