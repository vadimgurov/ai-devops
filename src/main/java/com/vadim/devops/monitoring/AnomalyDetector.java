package com.vadim.devops.monitoring;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AnomalyDetector {

    private final ConcurrentHashMap<String, String> lastServiceStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> lastHealthStatus = new ConcurrentHashMap<>();

    public List<Anomaly> detectServiceChange(String hostId, String serviceId, String currentStatus) {
        var key = hostId + "/" + serviceId;
        var prev = lastServiceStatus.put(key, currentStatus);
        var anomalies = new ArrayList<Anomaly>();

        var isActive = "active".equals(currentStatus);

        if (prev == null) {
            // Первый запуск: сразу репортим если сервис не активен
            if (!isActive) {
                anomalies.add(new Anomaly(Anomaly.Type.SERVICE_DOWN, hostId, serviceId,
                        "Сервис не активен при старте мониторинга: " + currentStatus));
            }
            return anomalies;
        }

        var wasActive = "active".equals(prev);
        if (wasActive && !isActive) {
            anomalies.add(new Anomaly(Anomaly.Type.SERVICE_DOWN, hostId, serviceId,
                    "Сервис перешёл в статус: " + currentStatus));
        } else if (!wasActive && isActive) {
            anomalies.add(new Anomaly(Anomaly.Type.SERVICE_RECOVERED, hostId, serviceId,
                    "Сервис восстановлен"));
        }
        return anomalies;
    }

    public List<Anomaly> detectHealthChange(String hostId, String serviceId, boolean healthy) {
        var key = hostId + "/" + serviceId;
        var prev = lastHealthStatus.put(key, healthy);
        var anomalies = new ArrayList<Anomaly>();

        if (prev == null) return anomalies; // health: только переходы, не первый запуск

        if (prev && !healthy) {
            anomalies.add(new Anomaly(Anomaly.Type.HEALTH_FAIL, hostId, serviceId, "Health check не прошёл"));
        } else if (!prev && healthy) {
            anomalies.add(new Anomaly(Anomaly.Type.HEALTH_RECOVERED, hostId, serviceId, "Health check восстановлен"));
        }
        return anomalies;
    }
}
