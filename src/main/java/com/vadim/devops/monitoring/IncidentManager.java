package com.vadim.devops.monitoring;

import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.llm.LlmAgent;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentEvent;
import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.telegram.ProgressTracker;
import com.vadim.devops.telegram.TelegramMarkdownConverter;
import com.vadim.devops.telegram.TelegramNotifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

@Service
public class IncidentManager {

    private static final Logger log = LoggerFactory.getLogger(IncidentManager.class);

    private final KnowledgeBaseService kb;
    private final InventoryLoader inventory;
    private final Optional<TelegramNotifier> telegram;
    private final ObjectProvider<LlmAgent> llmAgent;
    private final ObjectProvider<ProfilingService> profilingService;
    private final ProgressTracker progressTracker;
    private final InvestigationContext investigationContext;

    // In-memory: only running investigations (incidentId → Future)
    private final ConcurrentHashMap<String, Future<?>> active = new ConcurrentHashMap<>();
    private final ExecutorService investigationPool = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "investigation"));

    public IncidentManager(KnowledgeBaseService kb, InventoryLoader inventory,
                           Optional<TelegramNotifier> telegram,
                           ObjectProvider<LlmAgent> llmAgent,
                           ObjectProvider<ProfilingService> profilingService,
                           ProgressTracker progressTracker,
                           InvestigationContext investigationContext) {
        this.kb = kb;
        this.inventory = inventory;
        this.telegram = telegram;
        this.llmAgent = llmAgent;
        this.profilingService = profilingService;
        this.progressTracker = progressTracker;
        this.investigationContext = investigationContext;
    }

    @PostConstruct
    void init() {
        // Reset incidents stuck in INVESTIGATING state from a previous crash
        kb.findOpenIncidents().stream()
                .filter(i -> i.status() == Incident.Status.INVESTIGATING)
                .forEach(i -> {
                    log.warn("Resetting stuck INVESTIGATING → OPEN: {} — {}", i.id(), i.summary());
                    kb.saveIncident(i.withStatus(Incident.Status.OPEN));
                    telegram.ifPresent(t -> t.sendMessage(
                            "⚠️ Восстановлен инцидент после перезапуска: " + IncidentFormatter.htmlRef(i)));
                });
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    public void onAnomaly(Anomaly anomaly) {
        var alertTypes = inventory.findHost(anomaly.hostId())
                .map(h -> h.alertTypes())
                .orElse(null);
        if (alertTypes != null && !alertTypes.isEmpty() && !alertTypes.contains(anomaly.type().name())) return;

        switch (anomaly.type()) {
            case EXCEPTION_BURST -> {
                if (hasOpenIncident(anomaly.hostId(), anomaly.serviceId())) return;
                // Check if this is a recurrence of a previously investigated exception
                var exceptionClass = extractExceptionClass(anomaly.details());
                var prev = exceptionClass != null
                        ? kb.findSimilarIncidents(anomaly.serviceId(), exceptionClass).stream()
                                .filter(i -> anomaly.hostId().equals(i.hostId()))
                                .max(Comparator.comparing(Incident::startedAt))
                                .orElse(null)
                        : null;
                if (prev != null) {
                    var recurred = prev
                            .withStatus(Incident.Status.OPEN)
                            .addEvent(new IncidentEvent(Instant.now(), "recurrence",
                                    Map.of("details", anomaly.details())));
                    kb.saveIncident(recurred);
                    log.warn("Повтор инцидента {}: {}", prev.id(), exceptionClass);
                    telegram.ifPresent(t -> t.sendMessage(
                            "🔁 <b>Повтор</b> " + IncidentFormatter.htmlRef(recurred)
                            + "\n" + IncidentFormatter.escapeHtml(anomaly.details())
                            + "\n\nИнцидент уже расследовался. Нажми /incidents чтобы расследовать снова."));
                    return;
                }
                // New exception — create incident and investigate
                var incident = new Incident(
                        "inc-" + System.currentTimeMillis(),
                        anomaly.hostId(), anomaly.serviceId(),
                        Incident.Status.OPEN, Incident.Severity.HIGH,
                        Instant.now(), null,
                        anomaly.details(), null, null, null);
                kb.saveIncident(incident);
                log.warn("Инцидент открыт [OPEN]: {} — {}", incident.id(), incident.summary());
                telegram.ifPresent(t -> t.sendIncidentAlert(incident));
                tryPickUpWork();
            }
            case HEALTH_FAIL, METRIC_HIGH -> {
                if (hasOpenIncident(anomaly.hostId(), anomaly.serviceId())) return;
                var isCpuMetric = anomaly.type() == Anomaly.Type.METRIC_HIGH
                        && "cpu".equalsIgnoreCase(anomaly.serviceId());
                var status = isCpuMetric ? Incident.Status.PROFILING : Incident.Status.OPEN;
                var severity = anomaly.type() == Anomaly.Type.METRIC_HIGH
                        ? Incident.Severity.MEDIUM : Incident.Severity.HIGH;
                var incident = new Incident(
                        "inc-" + System.currentTimeMillis(),
                        anomaly.hostId(), anomaly.serviceId(),
                        status, severity,
                        Instant.now(), null,
                        anomaly.details(), null, null, null);
                kb.saveIncident(incident);
                log.warn("Инцидент открыт [{}]: {} — {}", status, incident.id(), incident.summary());
                telegram.ifPresent(t -> t.sendIncidentAlert(incident));
                if (isCpuMetric) {
                    var profiler = profilingService.getIfAvailable();
                    if (profiler != null) profiler.submit(incident);
                } else {
                    tryPickUpWork();
                }
            }
            case HEALTH_RECOVERED, METRIC_RECOVERED, SERVICE_RECOVERED -> {
                kb.findOpenIncidents().stream()
                        .filter(i -> anomaly.hostId().equals(i.hostId())
                                && anomaly.serviceId().equals(i.serviceId()))
                        .forEach(i -> {
                            if (i.status() == Incident.Status.INVESTIGATING) {
                                // Let investigation finish — LLM will call resolveIncident with findings
                                log.info("Метрика восстановилась, расследование {} продолжается", i.id());
                                kb.saveIncident(i.addEvent(new IncidentEvent(Instant.now(),
                                        "metric_recovered", Map.of("details", anomaly.details()))));
                                return;
                            }
                            if (i.status() == Incident.Status.PROFILING) {
                                var profiler = profilingService.getIfAvailable();
                                if (profiler != null) profiler.skipProfiling(i.id());
                                return; // skipProfiling transitions to OPEN, then recovery triggers again on next cycle
                            }
                            // OPEN — metric recovered before investigation started
                            var resolved = i.withStatus(Incident.Status.RESOLVED)
                                    .addEvent(new IncidentEvent(Instant.now(), "metric_recovered",
                                            Map.of("details", anomaly.details(),
                                                   "note", "Метрика восстановилась до начала расследования")));
                            kb.saveIncident(resolved);
                            log.info("Инцидент закрыт (автовосстановление): {}", i.id());
                            telegram.ifPresent(t -> t.sendMessage(
                                    "✅ Автоматически закрыт инцидент " + IncidentFormatter.htmlRef(resolved)
                                            + " — метрика восстановилась до начала расследования.\n"
                                            + IncidentFormatter.escapeHtml(anomaly.details())));
                        });
            }
            default -> {}
        }
    }

    // ── Investigation control ─────────────────────────────────────────────────

    /** Запустить расследование конкретного инцидента немедленно.
     *  @return false если уже идёт расследование или инцидент не OPEN */
    public synchronized boolean investigateNow(String incidentId) {
        if (!active.isEmpty()) return false;
        var agent = llmAgent.getIfAvailable();
        if (agent == null) return false;
        return kb.loadIncident(incidentId)
                .filter(i -> i.status() == Incident.Status.OPEN)  // PROFILING not eligible yet
                .map(i -> {
                    var open = kb.findOpenIncidents().stream()
                            .filter(o -> o.status() == Incident.Status.OPEN)
                            .toList();
                    closeDuplicates(open, i);
                    claim(i, agent);
                    return true;
                })
                .orElse(false);
    }

    /** Остановить все текущие расследования.
     *  @return true если было что останавливать */
    public boolean cancelInvestigation() {
        if (active.isEmpty()) return false;
        active.keySet().forEach(this::cancelIfRunning);
        return true;
    }

    // ── Incident state ────────────────────────────────────────────────────────

    public void updateHypothesis(String incidentId, String hypothesis, double confidence) {
        kb.loadIncident(incidentId).ifPresent(i -> kb.saveIncident(
                i.withHypothesis(hypothesis, confidence)
                 .addEvent(new IncidentEvent(Instant.now(), "hypothesis_updated",
                         Map.of("hypothesis", hypothesis, "confidence", confidence)))));
    }

    /** Закрыть инцидент. Не разрешает закрытие если сейчас идёт расследование (оператор должен сначала /stop). */
    public boolean resolve(String incidentId, String resolution) {
        if (active.containsKey(incidentId)) return false;
        return kb.loadIncident(incidentId)
                .filter(i -> i.status() != Incident.Status.RESOLVED)
                .map(i -> {
                    var resolved = i.withStatus(Incident.Status.RESOLVED)
                            .withHypothesis(resolution, 1.0)
                            .addEvent(new IncidentEvent(Instant.now(), "resolved",
                                    Map.of("resolution", resolution)));
                    kb.saveIncident(resolved);
                    telegram.ifPresent(t -> t.sendIncidentResolved(resolved));
                    return true;
                }).orElse(false);
    }

    /** Закрыть инцидент из Telegram-бота: останавливает расследование если идёт, затем закрывает. */
    public boolean forceResolve(String incidentId, String resolution) {
        cancelIfRunning(incidentId);
        return kb.loadIncident(incidentId)
                .filter(i -> i.status() != Incident.Status.RESOLVED)
                .map(i -> {
                    var resolved = i.withStatus(Incident.Status.RESOLVED)
                            .withHypothesis(resolution, 1.0)
                            .addEvent(new IncidentEvent(Instant.now(), "resolved",
                                    Map.of("resolution", resolution)));
                    kb.saveIncident(resolved);
                    telegram.ifPresent(t -> t.sendIncidentResolved(resolved));
                    return true;
                }).orElse(false);
    }

    /** Закрыть инцидент из расследования (вызывается LLM-инструментом). */
    public boolean resolveFromInvestigation(String incidentId, String resolution) {
        return kb.loadIncident(incidentId)
                .filter(i -> i.status() != Incident.Status.RESOLVED)
                .map(i -> {
                    var resolved = i.withStatus(Incident.Status.RESOLVED)
                            .withHypothesis(resolution, 1.0)
                            .addEvent(new IncidentEvent(Instant.now(), "resolved",
                                    Map.of("resolution", resolution)));
                    kb.saveIncident(resolved);
                    telegram.ifPresent(t -> t.sendIncidentResolved(resolved));
                    return true;
                }).orElse(false);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Collection<Incident> openIncidents() {
        return kb.findOpenIncidents();
    }

    public Optional<Incident> loadIncident(String incidentId) {
        return kb.loadIncident(incidentId);
    }

    public List<Incident> findSimilarIncidents(String serviceId, String keywords) {
        return kb.findSimilarIncidents(serviceId, keywords);
    }

    public boolean hasOpenIncident(String hostId, String serviceId) {
        return kb.findOpenIncidents().stream()
                .anyMatch(i -> hostId.equals(i.hostId()) && serviceId.equals(i.serviceId()));
    }

    public String activeIncidentId() {
        return active.keySet().stream().findFirst().orElse(null);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    synchronized void tryPickUpWork() {
        if (!active.isEmpty()) return;
        var agent = llmAgent.getIfAvailable();
        if (agent == null) return;
        var open = kb.findOpenIncidents().stream()
                .filter(i -> i.status() == Incident.Status.OPEN)
                .toList();
        if (open.isEmpty()) return;
        var toInvestigate = open.stream()
                .max(Comparator.comparing(Incident::startedAt))
                .orElseThrow();
        closeDuplicates(open, toInvestigate);
        claim(toInvestigate, agent);
    }

    private void closeDuplicates(List<Incident> allOpen, Incident chosen) {
        allOpen.stream()
                .filter(i -> !i.id().equals(chosen.id()))
                .filter(i -> chosen.hostId().equals(i.hostId()) && chosen.serviceId().equals(i.serviceId()))
                .forEach(i -> {
                    var resolved = i.withStatus(Incident.Status.RESOLVED)
                            .withHypothesis("Повторился, разбираемся в " + chosen.id(), 1.0)
                            .addEvent(new IncidentEvent(Instant.now(), "duplicate_closed",
                                    Map.of("superseded_by", chosen.id())));
                    kb.saveIncident(resolved);
                    log.info("Закрываю дубль {}: повторился в {}", i.id(), chosen.id());
                    telegram.ifPresent(t -> t.sendMessage(
                            "🔄 Инцидент " + IncidentFormatter.htmlRef(resolved)
                                    + " закрыт как дубль → " + IncidentFormatter.htmlRef(chosen)));
                });
    }

    private void claim(Incident incident, LlmAgent agent) {
        kb.saveIncident(incident.withStatus(Incident.Status.INVESTIGATING));
        var future = investigationPool.submit(() -> investigate(incident, agent));
        active.put(incident.id(), future);
    }

    private void cancelIfRunning(String incidentId) {
        var future = active.remove(incidentId);
        if (future == null || future.isDone()) return;
        future.cancel(true);
        kb.loadIncident(incidentId)
                .filter(i -> i.status() == Incident.Status.INVESTIGATING)
                .ifPresent(i -> kb.saveIncident(i.withStatus(Incident.Status.OPEN)));
        log.info("Расследование {} остановлено", incidentId);
    }

    private void investigate(Incident incident, LlmAgent agent) {
        // Re-check KB — incident may have been resolved since we claimed it
        var current = kb.loadIncident(incident.id()).orElse(null);
        if (current == null || current.status() == Incident.Status.RESOLVED) {
            log.info("Пропускаю расследование {}: инцидент уже закрыт", incident.id());
            active.remove(incident.id());
            return;
        }

        log.info("Начинаю расследование: {} — {}", incident.id(), incident.summary());
        boolean cancelled = false;

        var progressChatId = telegram.map(TelegramNotifier::getOperatorChatId).orElse(null);
        Integer progressMsgId = null;
        if (progressChatId != null && !progressChatId.isBlank()) {
            progressMsgId = telegram.get().sendAndGetId(progressChatId,
                    "⏳ Расследую " + IncidentFormatter.htmlRef(incident));
            if (progressMsgId != null) progressTracker.start(progressChatId, progressMsgId);
        }
        final var chatId = progressChatId;
        final var msgId = progressMsgId;

        investigationContext.set(current);
        try {
            var result = agent.askInIncidentContext(incident.id(), buildQuestion(current));
            progressTracker.clear();
            if (msgId != null) telegram.get().deleteMessage(chatId, msgId);
            telegram.ifPresent(t -> t.sendMessage(
                    "🔍 <b>Расследование завершено</b> для " + IncidentFormatter.htmlRef(incident) + ":\n"
                            + TelegramMarkdownConverter.convert(result)));
        } catch (Exception e) {
            progressTracker.clear();
            if (msgId != null) telegram.ifPresent(t -> t.deleteMessage(chatId, msgId));
            if (isCausedByInterrupt(e)) {
                cancelled = true;
                log.info("Расследование {} прервано", incident.id());
                var hypothesisPart = kb.loadIncident(incident.id())
                        .filter(i -> i.rootCauseHypothesis() != null)
                        .map(i -> "\n\nГипотеза на момент прерывания: " + i.rootCauseHypothesis()
                                + " (уверенность: " + (int) (i.confidence() * 100) + "%)")
                        .orElse("\n\nГипотеза не была сформулирована.");
                telegram.ifPresent(t -> t.sendMessage(
                        "⚠️ Расследование прервано для " + IncidentFormatter.htmlRef(incident) + "."
                                + hypothesisPart));
            } else {
                log.warn("Расследование {} упало: {}", incident.id(), e.getMessage());
                telegram.ifPresent(t -> t.sendMessage(
                        "❌ Расследование завершилось с ошибкой для " + IncidentFormatter.htmlRef(incident)
                                + ": <code>" + IncidentFormatter.escapeHtml(e.getMessage()) + "</code>"));
            }
        } finally {
            investigationContext.clear();
            active.remove(incident.id());
            if (!cancelled) {
                // If LLM didn't call resolveIncident, don't leave the incident stuck in INVESTIGATING
                kb.loadIncident(incident.id())
                        .filter(i -> i.status() == Incident.Status.INVESTIGATING)
                        .ifPresent(i -> kb.saveIncident(i.withStatus(Incident.Status.OPEN)));
                tryPickUpWork();
            }
        }
    }

    private static String buildQuestion(Incident incident) {
        var profilingData = incident.events() == null ? null : incident.events().stream()
                .filter(e -> "profiling_complete".equals(e.eventType()))
                .findFirst()
                .map(e -> e.payload().get("data"))
                .map(Object::toString)
                .orElse(null);

        var profilingSection = profilingData != null
                ? "\n\nДанные профайлинга CPU (уже собраны, не запускай профайлер повторно):\n" + profilingData + "\n"
                : "";

        return """
                Инцидент %s: %s (хост: %s, сервис: %s).
                %s
                Обязательные шаги (по порядку):
                1. searchSimilarIncidents — найди похожие инциденты для %s, ключевые слова из описания выше
                2. Если нашёл похожий — загрузи его через getIncident и изучи как решали
                3. getInventory — найди repoUrl для сервиса %s. Если repoUrl задан — вызови updateSourceCode(%s) чтобы получить свежий код, затем изучи его через bash (hostId=null): прочитай ключевые файлы чтобы понять архитектуру, точки отказа, критичные участки кода
                3.1. Для КАЖДОЙ найденной кодовой проблемы обязательно открой текущий файл и проверь, что дефект реально существует в текущем коде, а не только в похожем инциденте, памяти модели или старом описании
                3.2. Если проблема уже исправлена в текущем коде — явно исключи её из root cause и пометь как историческую/уже исправленную, не относящуюся к текущему инциденту
                4. Собери факты: логи, статус процессов, метрики через bash
                5. Если встретил незнакомую ошибку/исключение — поищи в интернете через search
                6. updateIncidentHypothesis — сохрани гипотезу о причине с учётом знания кода, но включай туда только проблемы, подтверждённые текущими файлами и текущими фактами инцидента
                7. resolveIncident — ОБЯЗАТЕЛЬНО в конце: закрой инцидент с итоговым выводом (даже если метрика уже восстановилась)
                """.formatted(incident.id(), incident.summary(), incident.hostId(), incident.serviceId(),
                profilingSection, incident.serviceId(), incident.serviceId(), incident.serviceId());
    }

    private static final Pattern EXCEPTION_CLASS_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9.]*(?:Exception|Error|Panic|Traceback)");

    static String extractExceptionClass(String details) {
        if (details == null) return null;
        var m = EXCEPTION_CLASS_PATTERN.matcher(details);
        return m.find() ? m.group() : null;
    }

    private static boolean isCausedByInterrupt(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }
}
