package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.model.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExceptionScanner {

    private static final Logger log = LoggerFactory.getLogger(ExceptionScanner.class);

    private static final String GREP_PATTERN =
            "grep -E '(Exception|Traceback|FATAL|PANIC|panic:)' 2>/dev/null"
            + " | grep -vE '^\\s+at '"               // skip stack trace frames
            + " | grep -vE '(GET /health|GET /metrics|HTTP/1\\.x request|Http2Exception|client preface)'"
            + " | grep -v '^#' | head -5";

    private static final DateTimeFormatter JOURNALCTL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final InventoryLoader inventory;
    private final BashRunner runner;
    private final IncidentManager incidentManager;

    // Per "hostId/serviceId": fingerprint of last reported exception (first line, trimmed to 120 chars)
    private final ConcurrentHashMap<String, String> lastFingerprint = new ConcurrentHashMap<>();
    // Per "hostId/serviceId": when we last scanned (to pass as --since)
    private final ConcurrentHashMap<String, Instant> lastScan = new ConcurrentHashMap<>();

    public ExceptionScanner(InventoryLoader inventory, BashRunner runner, IncidentManager incidentManager) {
        this.inventory = inventory;
        this.runner = runner;
        this.incidentManager = incidentManager;
    }

    @Scheduled(fixedDelayString = "${devops.monitoring.exception-scan-interval-ms:120000}",
               initialDelay = 60_000)
    void scan() {
        for (var host : inventory.allHosts()) {
            if (host.services() == null) continue;
            for (var service : host.services()) {
                var key = host.id() + "/" + service.id();
                var since = lastScan.getOrDefault(key, Instant.now().minusSeconds(300));
                lastScan.put(key, Instant.now());

                var logCmd = buildLogCmd(host.sshTarget(), service, since);
                if (logCmd == null) continue;

                var output = runner.run(logCmd).stdout().trim();
                if (output.isBlank()) continue;

                var fingerprint = fingerprint(output);
                if (fingerprint.equals(lastFingerprint.get(key))) continue;
                lastFingerprint.put(key, fingerprint);

                log.warn("Обнаружены исключения в логах {}/{}: {}", host.id(), service.id(), fingerprint);
                var summary = "Исключения в логах: " + fingerprint;
                incidentManager.onAnomaly(new Anomaly(
                        Anomaly.Type.EXCEPTION_BURST, host.id(), service.id(), summary));
            }
        }
    }

    private String buildLogCmd(String sshTarget, ServiceConfig svc, Instant since) {
        String fetchCmd;
        if (svc.systemdUnit() != null && !svc.systemdUnit().isBlank()) {
            var sinceStr = JOURNALCTL_FMT.format(since);
            fetchCmd = "journalctl -u %s --since '%s' --no-pager -o short-iso 2>/dev/null"
                    .formatted(svc.systemdUnit(), sinceStr);
        } else if (svc.containerName() != null && !svc.containerName().isBlank()) {
            fetchCmd = "docker logs %s --since %d 2>&1".formatted(svc.containerName(), since.getEpochSecond());
        } else {
            return null;
        }
        return "ssh %s %s".formatted(sshTarget, shellQuote(fetchCmd + " | " + GREP_PATTERN));
    }

    private static String fingerprint(String output) {
        return output.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .findFirst()
                .map(l -> l.length() > 120 ? l.substring(0, 120) : l)
                .orElse(output.substring(0, Math.min(output.length(), 120)));
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
