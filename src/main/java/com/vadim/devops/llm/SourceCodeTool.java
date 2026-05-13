package com.vadim.devops.llm;

import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.repo.RepoManager;
import com.vadim.devops.telegram.ProgressService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class SourceCodeTool {

    private final InventoryLoader inventory;
    private final RepoManager repoManager;
    private final ProgressService progressService;

    public SourceCodeTool(InventoryLoader inventory, RepoManager repoManager,
                          ProgressService progressService) {
        this.inventory = inventory;
        this.repoManager = repoManager;
        this.progressService = progressService;
    }

    @Tool(description = """
            Склонировать или обновить (git pull) исходный код сервиса локально.
            Вызывай перед тем как читать код через bash (hostId=null).
            Несколько сервисов из одной монорепы разделяют одну локальную копию — клон происходит один раз.
            Возвращает локальный путь к репозиторию.
            """)
    public String updateSourceCode(String serviceId) {
        progressService.update("📥 Обновляю исходники " + serviceId + "...");
        for (var host : inventory.allHosts()) {
            if (host.services() == null) continue;
            for (var svc : host.services()) {
                if (!serviceId.equals(svc.id())) continue;
                if (svc.repoUrl() == null) {
                    return "У сервиса %s не задан repoUrl.".formatted(serviceId);
                }
                try {
                    var path = repoManager.ensureFresh(svc.repoUrl());
                    // Persist the local path so the agent can find sources without re-cloning
                    if (!path.equals(svc.sourcesPath())) {
                        var updated = new com.vadim.devops.model.ServiceConfig(
                                svc.id(), svc.name(), svc.hostId(), svc.runtime(), svc.systemdUnit(),
                                svc.containerName(), svc.healthCheck(), svc.versionUrl(), path,
                                svc.repoUrl(), svc.logsCommand(), svc.healthCheckMinDurationMs(),
                                svc.configFiles());
                        try { inventory.saveService(svc.hostId(), updated); } catch (Exception ignored) {}
                    }
                    return "Исходники обновлены. Локальный путь: " + path;
                } catch (Exception e) {
                    return "Ошибка git операции: " + e.getMessage();
                }
            }
        }
        return "Сервис %s не найден в инвентори.".formatted(serviceId);
    }
}