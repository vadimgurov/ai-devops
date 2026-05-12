package com.vadim.devops.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.model.TelemetryCheck;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class InventoryLoader {

    private static final Logger log = LoggerFactory.getLogger(InventoryLoader.class);

    private final Path hostsDir;
    private final ObjectMapper yaml;
    private final Map<String, Host> hosts = new LinkedHashMap<>();

    public InventoryLoader(DevopsProperties props, @Qualifier("yamlMapper") ObjectMapper yaml) {
        this.hostsDir = Path.of(props.kb().path(), "hosts");
        this.yaml = yaml;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(hostsDir)) {
            log.warn("Hosts directory not found: {}", hostsDir);
            return;
        }
        try (var stream = Files.list(hostsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .forEach(this::loadHost);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("Loaded {} hosts from inventory", hosts.size());
    }

    private void loadHost(Path hostFile) {
        try {
            var host = yaml.readValue(hostFile.toFile(), Host.class);
            var services = loadServices(hostsDir.resolve(host.id()).resolve("services"));
            hosts.put(host.id(), host.withServices(services));
        } catch (IOException e) {
            log.error("Failed to load host from {}", hostFile, e);
        }
    }

    private List<ServiceConfig> loadServices(Path servicesDir) throws IOException {
        if (!Files.exists(servicesDir)) return List.of();
        try (var stream = Files.list(servicesDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .map(p -> {
                        try {
                            return yaml.readValue(p.toFile(), ServiceConfig.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
    }

    public synchronized Collection<Host> allHosts() {
        return List.copyOf(hosts.values());
    }

    public synchronized Optional<Host> findHost(String id) {
        return Optional.ofNullable(hosts.get(id));
    }

    public synchronized Optional<ServiceConfig> findService(String hostId, String serviceId) {
        return findHost(hostId)
                .flatMap(h -> h.services().stream().filter(s -> s.id().equals(serviceId)).findFirst());
    }

    public synchronized void saveHost(Host host) throws IOException {
        var hostFile = hostsDir.resolve(host.id() + ".yaml");
        Files.createDirectories(hostsDir);
        yaml.writeValue(hostFile.toFile(), host);
        // Preserve in-memory services — they live in a separate directory and are not part of host YAML
        var existing = hosts.get(host.id());
        var services = (existing != null && (host.services() == null || host.services().isEmpty()))
                ? existing.services() : host.services();
        hosts.put(host.id(), host.withServices(services));
        log.info("Saved host: {}", host.id());
    }

    public synchronized void saveService(String hostId, ServiceConfig service) throws IOException {
        var servicesDir = hostsDir.resolve(hostId).resolve("services");
        Files.createDirectories(servicesDir);
        yaml.writeValue(servicesDir.resolve(service.id() + ".yaml").toFile(), service);
        var host = hosts.get(hostId);
        if (host != null) {
            var services = new ArrayList<>(host.services() != null ? host.services() : List.of());
            services.removeIf(s -> s.id().equals(service.id()));
            services.add(service);
            hosts.put(hostId, host.withServices(services));
        }
        log.info("Saved service: {}/{}", hostId, service.id());
    }

    public synchronized void saveTelemetryCheck(String hostId, TelemetryCheck check) throws IOException {
        var host = hosts.get(hostId);
        if (host == null) throw new IllegalArgumentException("Host not found: " + hostId);
        var telemetry = new ArrayList<>(host.telemetry() != null ? host.telemetry() : List.of());
        // Remove by name OR by identical command — prevent duplicate checks for the same resource
        telemetry.removeIf(t -> t.name().equals(check.name()) || t.command().equals(check.command()));
        telemetry.add(check);
        var updated = host.withTelemetry(telemetry);
        yaml.writeValue(hostsDir.resolve(hostId + ".yaml").toFile(), updated);
        hosts.put(hostId, updated);
        log.info("Saved telemetry check: {}/{}", hostId, check.name());
    }
}
