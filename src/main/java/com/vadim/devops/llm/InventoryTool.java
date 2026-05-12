package com.vadim.devops.llm;

import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.model.TelemetryCheck;
import com.vadim.devops.telegram.ProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class InventoryTool {

    private static final Logger log = LoggerFactory.getLogger(InventoryTool.class);

    private final InventoryLoader inventoryLoader;
    private final ProgressService progressService;

    public InventoryTool(InventoryLoader inventoryLoader, ProgressService progressService) {
        this.inventoryLoader = inventoryLoader;
        this.progressService = progressService;
    }

    @Tool(description = "Показать все хосты и сервисы из инвентори: id, sshTarget, окружение, юниты, healthCheck, repoUrl (git-репозиторий с исходниками).")
    public String getInventory() {
        progressService.update("📋 Читаю инвентори...");
        var sb = new StringBuilder();
        inventoryLoader.allHosts().forEach(h -> {
            sb.append("Хост: ").append(h.id())
              .append(" name=").append(h.name())
              .append(" ssh=").append(h.sshTarget())
              .append(" env=").append(h.env());
            if (h.alertTypes() != null && !h.alertTypes().isEmpty()) {
                sb.append(" alertTypes=").append(h.alertTypes());
            }
            sb.append("\n");
            if (h.services() != null) {
                h.services().forEach(s -> {
                    sb.append("  сервис: ").append(s.id())
                      .append(" (").append(s.runtime()).append(")")
                      .append(" unit=").append(s.systemdUnit())
                      .append(" health=").append(s.healthCheck() != null ? s.healthCheck() : "none");
                    if (s.repoUrl() != null) {
                        sb.append(" repoUrl=").append(s.repoUrl())
                          .append(" [вызови updateSourceCode перед чтением кода]");
                    }
                    sb.append("\n");
                });
            }
            if (h.telemetry() != null && !h.telemetry().isEmpty()) {
                h.telemetry().forEach(t ->
                    sb.append("  телеметрия: ").append(t.name())
                      .append(" threshold=").append(t.threshold())
                      .append(" cmd=").append(t.command()).append("\n"));
            }
        });
        var result = sb.toString();
        log.debug("↩ inventory: {} hosts", inventoryLoader.allHosts().size());
        return result;
    }

    @Tool(description = "Сохранить новый хост в инвентори. Вызывай после того как собрал всю информацию о хосте через SSH.")
    public String saveHost(String id, String name, String env, String ip, String sshTarget, String notes) {
        log.info("saveHost id={} name={} sshTarget={}", id, name, sshTarget);
        try {
            var host = new Host(id, name, env, ip, sshTarget, notes, List.of(), List.of(), null);
            inventoryLoader.saveHost(host);
            var result = "Хост '%s' сохранён в инвентори.".formatted(id);
            return result;
        } catch (IOException e) {
            return "Ошибка сохранения хоста: " + e.getMessage();
        }
    }

    @Tool(description = """
            Задать фильтр типов алертов для хоста. Если список задан — агент реагирует ТОЛЬКО на эти типы аномалий.
            Типы: SERVICE_DOWN, HEALTH_FAIL, METRIC_HIGH, EXCEPTION_BURST.
            Пустой список или null — реагировать на всё (поведение по умолчанию).
            Пример: ["EXCEPTION_BURST"] — только исключения в логах, игнорировать CPU/RAM/health.
            """)
    public String setHostAlertTypes(String hostId, List<String> alertTypes) {
        log.info("setHostAlertTypes hostId={} alertTypes={}", hostId, alertTypes);
        return inventoryLoader.findHost(hostId)
                .map(host -> {
                    try {
                        inventoryLoader.saveHost(new Host(
                                host.id(), host.name(), host.env(), host.ip(), host.sshTarget(),
                                host.notes(), host.services(), host.telemetry(),
                                (alertTypes == null || alertTypes.isEmpty()) ? null : alertTypes));
                        return "alertTypes для хоста '%s' обновлён: %s".formatted(hostId, alertTypes);
                    } catch (IOException e) {
                        return "Ошибка: " + e.getMessage();
                    }
                })
                .orElse("Хост '%s' не найден.".formatted(hostId));
    }

    @Tool(description = """
            Добавить или обновить телеметрическую проверку для хоста.
            Команда должна выводить одно число (например процент). threshold — при превышении открывается инцидент.
            minDurationMs — минимальная длительность нарушения (в мс) перед открытием инцидента.
            Например 300000 (5 минут) чтобы игнорировать кратковременные CPU-спайки. null — реагировать сразу.
            """)
    public String saveTelemetryCheck(String hostId, String name, String command, double threshold, Long minDurationMs) {
        log.info("saveTelemetryCheck hostId={} name={} threshold={} minDurationMs={}", hostId, name, threshold, minDurationMs);
        try {
            inventoryLoader.saveTelemetryCheck(hostId, new TelemetryCheck(name, command, threshold, minDurationMs));
            var result = "Телеметрия '%s' сохранена для хоста '%s'.".formatted(name, hostId);
            return result;
        } catch (Exception e) {
            return "Ошибка: " + e.getMessage();
        }
    }

    @Tool(description = "Сохранить сервис для хоста в инвентори. Вызывай после того как выяснил детали сервиса через SSH. repoUrl — git-репозиторий с исходниками (например git@github.com:user/repo.git), null если неизвестен. healthCheck — shell-команда для проверки живости (exit 0 = жив), например: curl -fsS --max-time 5 http://localhost:8080/actuator/health > /dev/null")
    public String saveService(String hostId, String id, String name, String runtime,
                              String systemdUnit, String healthCheck, String repoUrl,
                              List<String> configFiles, List<String> allowedActions) {
        log.info("saveService hostId={} id={} unit={}", hostId, id, systemdUnit);
        try {
            var service = new ServiceConfig(id, name, hostId, runtime, systemdUnit,
                    null, healthCheck, null, null, repoUrl, configFiles, allowedActions);
            inventoryLoader.saveService(hostId, service);
            var result = "Сервис '%s' сохранён для хоста '%s'.".formatted(id, hostId);
            return result;
        } catch (IOException e) {
            return "Ошибка сохранения сервиса: " + e.getMessage();
        }
    }
}
