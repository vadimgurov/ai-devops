package com.vadim.devops.monitoring;

import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.kb.InventoryLoader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final InventoryLoader inventory;
    private final BashRunner runner;
    private final AnomalyDetector anomalyDetector;
    private final IncidentManager incidentManager;
    private final ConcurrentHashMap<String, Boolean> metricBreaching = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> breachStart = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> incidentFired = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    @PreDestroy
    void stop() { stopping.set(true); }

    public MonitoringScheduler(InventoryLoader inventory, BashRunner runner,
                               AnomalyDetector anomalyDetector, IncidentManager incidentManager) {
        this.inventory = inventory;
        this.runner = runner;
        this.anomalyDetector = anomalyDetector;
        this.incidentManager = incidentManager;
    }

    @Scheduled(fixedDelayString = "${devops.monitoring.cheap-check-interval-ms}")
    public void cheapCheck() {
        for (var host : inventory.allHosts()) {
            if (host.services() == null) continue;
            for (var service : host.services()) {
                if (stopping.get()) return;
                if (service.healthCheck() == null) continue;
                var result = runner.run("ssh %s '%s'".formatted(
                        host.sshTarget(), service.healthCheck().replace("'", "'\\''")));
                if (stopping.get() || result.interrupted()) return;
                anomalyDetector.detectHealthChange(host.id(), service.id(), result.success())
                        .forEach(incidentManager::onAnomaly);
            }
        }
    }

    @Scheduled(fixedDelayString = "${devops.monitoring.slow-check-interval-ms}")
    public void slowCheck() {
        for (var host : inventory.allHosts()) {
            if (host.telemetry() == null || host.telemetry().isEmpty()) continue;
            for (var check : host.telemetry()) {
                if (stopping.get()) return;
                var result = runner.run("ssh %s '%s'".formatted(
                        host.sshTarget(), check.command().replace("'", "'\\''")));
                if (stopping.get() || result.interrupted()) return;
                var raw = result.stdout().trim();
                double value;
                try {
                    value = Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    log.debug("Telemetry {}/{}: не удалось распарсить '{}'", host.id(), check.name(), raw);
                    continue;
                }
                var key = host.id() + "/" + check.name();
                var wasBreaching = metricBreaching.getOrDefault(key, false);
                var isBreaching = value >= check.threshold();
                metricBreaching.put(key, isBreaching);
                var details = "%s=%.1f (порог %.0f)".formatted(check.name(), value, check.threshold());
                var minMs = check.minDurationMs() != null ? check.minDurationMs() : 0L;
                if (isBreaching) {
                    if (!wasBreaching) breachStart.put(key, Instant.now());
                    if (!incidentFired.getOrDefault(key, false)) {
                        var elapsed = java.time.Duration.between(breachStart.get(key), Instant.now()).toMillis();
                        if (elapsed >= minMs) {
                            incidentManager.onAnomaly(new Anomaly(Anomaly.Type.METRIC_HIGH, host.id(), check.name(), details));
                            incidentFired.put(key, true);
                        }
                    }
                } else if (wasBreaching) {
                    if (incidentFired.getOrDefault(key, false)) {
                        incidentManager.onAnomaly(new Anomaly(Anomaly.Type.METRIC_RECOVERED, host.id(), check.name(), details));
                    }
                    breachStart.remove(key);
                    incidentFired.remove(key);
                }
            }
        }
    }
}
