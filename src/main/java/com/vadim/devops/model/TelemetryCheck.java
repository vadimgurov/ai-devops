package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryCheck(
        String name,
        String command,
        double threshold,
        Long minDurationMs
) {}
