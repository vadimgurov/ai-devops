package com.vadim.devops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationMessage(
        Instant ts,
        String role,
        String content
) {}
