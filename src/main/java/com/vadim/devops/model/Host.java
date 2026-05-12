package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Host(
        String id,
        String name,
        String env,
        String ip,
        String sshTarget,
        String notes,
        List<ServiceConfig> services,
        List<TelemetryCheck> telemetry,
        List<String> alertTypes
) {
    public Host withServices(List<ServiceConfig> services) {
        return new Host(id, name, env, ip, sshTarget, notes, services, telemetry, alertTypes);
    }

    public Host withTelemetry(List<TelemetryCheck> telemetry) {
        return new Host(id, name, env, ip, sshTarget, notes, services, telemetry, alertTypes);
    }
}
