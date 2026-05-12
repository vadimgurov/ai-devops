package com.vadim.devops.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.model.ConversationMessage;
import com.vadim.devops.model.Incident;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class KnowledgeBaseService {

    private final Path root;
    private final ObjectMapper json;

    public KnowledgeBaseService(DevopsProperties props, ObjectMapper json) {
        this.root = Path.of(props.kb().path());
        this.json = json;
    }

    // ── Incidents ──────────────────────────────────────────────────────────

    public void saveIncident(Incident incident) {
        write(incidentDir(incident.id()).resolve("incident.json"), incident);
    }

    public Optional<Incident> loadIncident(String id) {
        return read(incidentDir(id).resolve("incident.json"), Incident.class);
    }

    public List<Incident> findOpenIncidents() {
        return findAll("incidents", "incident.json", Incident.class).stream()
                .filter(i -> i.status() != Incident.Status.RESOLVED)
                .toList();
    }

    public List<Incident> findSimilarIncidents(String serviceId, String keywords) {
        var terms = keywords == null ? new String[0] : keywords.toLowerCase().split("\\s+");
        return findAll("incidents", "incident.json", Incident.class).stream()
                .filter(i -> i.status() == Incident.Status.RESOLVED)
                .filter(i -> serviceId.equals(i.serviceId()))
                .filter(i -> matchesAnyTerm(i, terms))
                .toList();
    }

    private static boolean matchesAnyTerm(Incident i, String[] terms) {
        if (terms.length == 0) return true;
        var text = ((i.summary() != null ? i.summary() : "") + " " +
                    (i.rootCauseHypothesis() != null ? i.rootCauseHypothesis() : "")).toLowerCase();
        for (var term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    // ── Conversation (agent memory) ─────────────────────────────────────────

    public void saveConversation(String incidentId, List<ConversationMessage> messages) {
        write(incidentDir(incidentId).resolve("conversation.json"), messages);
    }

    public List<ConversationMessage> loadConversation(String incidentId) {
        var file = incidentDir(incidentId).resolve("conversation.json");
        if (!Files.exists(file)) return List.of();
        return readList(file, ConversationMessage.class);
    }

    public void saveSession(List<ConversationMessage> messages) {
        write(root.resolve("conversations/session-" + LocalDate.now() + ".json"), messages);
    }

    public List<ConversationMessage> loadTodaySession() {
        var file = root.resolve("conversations/session-" + LocalDate.now() + ".json");
        if (!Files.exists(file)) return List.of();
        return readList(file, ConversationMessage.class);
    }

    public void clearTodaySession() {
        var file = root.resolve("conversations/session-" + LocalDate.now() + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось очистить историю: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Path incidentDir(String id) {
        return root.resolve("incidents/" + id);
    }

    private void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            json.writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> Optional<T> read(Path path, Class<T> type) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(json.readValue(path.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> List<T> readList(Path path, Class<T> elementType) {
        try {
            return json.readValue(path.toFile(),
                    json.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> List<T> findAll(String dir, String fileSuffix, Class<T> type) {
        var dirPath = root.resolve(dir);
        if (!Files.exists(dirPath)) return List.of();
        try (var stream = Files.walk(dirPath)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(fileSuffix))
                    .flatMap(p -> read(p, type).stream())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
