package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceConfig(
        String id,
        String name,
        String hostId,
        String runtime,
        String systemdUnit,
        String containerName,   // docker container name, for PID lookup
        String healthCheck,
        String versionUrl,
        String sourcesPath,
        String repoUrl,
        String logsCommand,     // shell command to read logs, e.g. journalctl -u crm.service -n 200
        Long healthCheckMinDurationMs, // min consecutive failure duration before incident (grace period for redeploys)
        List<String> configFiles,
        List<String> allowedActions
) {}
