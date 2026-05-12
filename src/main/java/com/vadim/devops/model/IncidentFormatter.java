package com.vadim.devops.model;

public final class IncidentFormatter {

    private IncidentFormatter() {
    }

    public static String plainRef(Incident incident) {
        return "%s (хост: %s, сервис: %s, проблема: %s)".formatted(
                incident.id(),
                safe(incident.hostId()),
                safe(incident.serviceId()),
                safe(incident.summary()));
    }

    public static String htmlRef(Incident incident) {
        return "<code>%s</code> (хост: <code>%s</code>, сервис: <code>%s</code>, проблема: %s)".formatted(
                escapeHtml(incident.id()),
                escapeHtml(safe(incident.hostId())),
                escapeHtml(safe(incident.serviceId())),
                escapeHtml(safe(incident.summary())));
    }

    public static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
