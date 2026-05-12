package com.vadim.devops.monitoring;

public record Anomaly(
        Type type,
        String hostId,
        String serviceId,
        String details
) {
    public enum Type { SERVICE_DOWN, SERVICE_RECOVERED, HEALTH_FAIL, HEALTH_RECOVERED, METRIC_HIGH, METRIC_RECOVERED, EXCEPTION_BURST }
}
