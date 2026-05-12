package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Incident(
        String id,
        String hostId,
        String serviceId,
        Status status,
        Severity severity,
        Instant startedAt,
        Instant resolvedAt,
        String summary,
        String rootCauseHypothesis,
        Double confidence,
        List<IncidentEvent> events
) {
    public enum Status { PROFILING, OPEN, INVESTIGATING, RESOLVED }
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public Incident withStatus(Status status) {
        return new Incident(id, hostId, serviceId, status, severity, startedAt,
                status == Status.RESOLVED ? Instant.now() : resolvedAt,
                summary, rootCauseHypothesis, confidence, events);
    }

    public Incident withHypothesis(String hypothesis, double confidence) {
        return new Incident(id, hostId, serviceId, status, severity, startedAt,
                resolvedAt, summary, hypothesis, confidence, events);
    }

    public Incident addEvent(IncidentEvent event) {
        var updated = new ArrayList<>(events != null ? events : List.of());
        updated.add(event);
        return new Incident(id, hostId, serviceId, status, severity, startedAt,
                resolvedAt, summary, rootCauseHypothesis, confidence, updated);
    }
}
