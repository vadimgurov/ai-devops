package com.vadim.devops.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.model.Incident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @TempDir Path tempDir;

    KnowledgeBaseService kb;

    @BeforeEach
    void setUp() {
        var props = mock(DevopsProperties.class);
        var kbProps = mock(DevopsProperties.KbProperties.class);
        when(props.kb()).thenReturn(kbProps);
        when(kbProps.path()).thenReturn(tempDir.toString());

        var json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        kb = new KnowledgeBaseService(props, json);
    }

    @Test
    void saveAndLoadIncident() {
        var incident = new Incident("inc-001", "host-1", "svc-1",
                Incident.Status.OPEN, Incident.Severity.HIGH,
                Instant.now(), null, "Service down", null, null, List.of());

        kb.saveIncident(incident);
        var loaded = kb.loadIncident("inc-001");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo("inc-001");
        assertThat(loaded.get().status()).isEqualTo(Incident.Status.OPEN);
    }

    @Test
    void loadIncident_notFound_returnsEmpty() {
        assertThat(kb.loadIncident("nonexistent")).isEmpty();
    }

    @Test
    void findOpenIncidents_excludesResolved() {
        var open = new Incident("inc-001", "h", "s", Incident.Status.OPEN,
                Incident.Severity.LOW, Instant.now(), null, "open", null, null, List.of());
        var resolved = new Incident("inc-002", "h", "s", Incident.Status.RESOLVED,
                Incident.Severity.LOW, Instant.now(), Instant.now(), "resolved", null, null, List.of());

        kb.saveIncident(open);
        kb.saveIncident(resolved);

        assertThat(kb.findOpenIncidents()).hasSize(1)
                .extracting(Incident::id).containsExactly("inc-001");
    }

    @Test
    void findSimilarIncidents_onlyResolved_matchesKeyword() {
        var resolved = new Incident("inc-001", "h", "svc-1", Incident.Status.RESOLVED,
                Incident.Severity.HIGH, Instant.now(), Instant.now(), "OutOfMemoryError in heap", null, null, List.of());
        var open = new Incident("inc-002", "h", "svc-1", Incident.Status.OPEN,
                Incident.Severity.HIGH, Instant.now(), null, "OutOfMemoryError again", null, null, List.of());

        kb.saveIncident(resolved);
        kb.saveIncident(open);

        var result = kb.findSimilarIncidents("svc-1", "OutOfMemoryError");

        assertThat(result).hasSize(1)
                .extracting(Incident::id).containsExactly("inc-001");
    }

    @Test
    void findSimilarIncidents_multipleKeywords_matchesAny() {
        var resolved = new Incident("inc-001", "h", "svc-1", Incident.Status.RESOLVED,
                Incident.Severity.HIGH, Instant.now(), Instant.now(), "high cpu usage", null, null, List.of());

        kb.saveIncident(resolved);

        assertThat(kb.findSimilarIncidents("svc-1", "cpu disk")).hasSize(1);
        assertThat(kb.findSimilarIncidents("svc-1", "memory")).isEmpty();
    }
}
