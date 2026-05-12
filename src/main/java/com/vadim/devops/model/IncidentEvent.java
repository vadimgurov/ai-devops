package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentEvent(
        Instant ts,
        String eventType,
        Map<String, Object> payload
) {}
