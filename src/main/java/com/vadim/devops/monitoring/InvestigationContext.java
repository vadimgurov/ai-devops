package com.vadim.devops.monitoring;

import com.vadim.devops.model.Incident;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Thread-local holder for the incident currently being investigated on this thread. */
@Component
public class InvestigationContext {

    private final ThreadLocal<Incident> current = new ThreadLocal<>();

    public void set(Incident incident) { current.set(incident); }

    public void clear() { current.remove(); }

    public Optional<Incident> get() { return Optional.ofNullable(current.get()); }
}
