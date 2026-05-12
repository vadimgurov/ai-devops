package com.vadim.devops.llm;

import com.vadim.devops.bash.BashService;
import com.vadim.devops.kb.CommandRegistry;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.model.Host;
import com.vadim.devops.monitoring.InvestigationContext;
import com.vadim.devops.telegram.ApprovalService;
import com.vadim.devops.telegram.ProgressTracker;
import com.vadim.devops.telegram.ProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Component
public class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private final BashService bashService;
    private final InventoryLoader inventory;
    private final CommandRegistry commandRegistry;
    private final ApprovalService approvalService;
    private final ProgressService progressService;
    private final ProgressTracker progressTracker;
    private final InvestigationContext investigationContext;

    public BashTool(BashService bashService, InventoryLoader inventory,
                    CommandRegistry commandRegistry, ApprovalService approvalService,
                    ProgressService progressService, ProgressTracker progressTracker,
                    InvestigationContext investigationContext) {
        this.bashService = bashService;
        this.inventory = inventory;
        this.commandRegistry = commandRegistry;
        this.approvalService = approvalService;
        this.progressService = progressService;
        this.progressTracker = progressTracker;
        this.investigationContext = investigationContext;
    }

    @Tool(description = """
            Выполнить команду на удалённом хосте через SSH или локально.

            hostId:
              - null или "null" → выполнить ЛОКАЛЬНО на машине агента
              - id хоста        → выполнить на удалённом сервере через SSH

            command — полная shell-команда (без ssh-префикса).
            Примеры: "journalctl -u crm.service --since '1 hour ago' | grep -i error | tail -50"
                     "docker logs deploy-bloggers-1 --tail 100 2>&1"
                     "df -h && free -m"

            Read-only команды (journalctl, docker logs/ps/inspect, df, free, cat, ps, curl, …)
            выполняются автоматически. Write-действия (systemctl restart, rm, docker stop, …)
            требуют подтверждения оператора. Неизвестные команды тоже запрашивают подтверждение.
            """)
    public String bash(String hostId, String command) {
        log.debug("⚙ bash › host={} cmd={}", hostId,
                command != null ? command.substring(0, Math.min(120, command.length())) : "null");

        if (command == null || command.isBlank()) return "Команда не указана.";
        if (isGitSourceOp(command)) {
            return "⛔ git clone/pull через bash запрещён. Используй инструмент updateSourceCode(serviceId).";
        }

        var cmdPreview = command.length() > 80 ? command.substring(0, 80) + "…" : command;
        var hostLabel = (hostId == null || hostId.isBlank() || hostId.equalsIgnoreCase("null")) ? "local" : hostId;
        progressService.update("🔧 [" + hostLabel + "] <code>$ " + escapeHtml(cmdPreview) + "</code>");

        if (hostId == null || hostId.isBlank() || hostId.equalsIgnoreCase("null")) {
            return execLocal(command);
        }

        var hostOpt = inventory.findHost(hostId);
        if (hostOpt.isEmpty()) {
            var available = inventory.allHosts().stream()
                    .map(Host::id).collect(Collectors.joining(", "));
            return printAndReturn("Неизвестный хост: '%s'. Доступные: %s".formatted(hostId, available));
        }
        var host = hostOpt.get();
        var fullCmd = sshWrap(host, command);

        return switch (commandRegistry.classify(command)) {
            case READ_ONLY -> printAndReturn(format(bashService.exec(fullCmd)));
            case WRITE_ACTION -> {
                if (requestAndWait(null, "Write-команда на %s:\n%s".formatted(hostId, fullCmd), false)
                        != ApprovalService.Decision.YES) yield "Отклонено оператором.";
                yield printAndReturn(format(bashService.exec(fullCmd)));
            }
            case UNKNOWN -> {
                var decision = requestAndWait(null,
                        "Неизвестная команда на %s:\n%s".formatted(hostId, fullCmd), true);
                yield switch (decision) {
                    case YES_ALWAYS -> {
                        commandRegistry.addReadOnlyPrefix(command);
                        yield printAndReturn(format(bashService.exec(fullCmd)));
                    }
                    case YES -> printAndReturn(format(bashService.exec(fullCmd)));
                    case NO -> "Отклонено оператором.";
                };
            }
        };
    }

    @Tool(description = "Показать все разрешённые команды: read-only префиксы и write-действия")
    public String listCommands() {
        var sb = new StringBuilder("READ-ONLY PREFIXES:\n");
        commandRegistry.allReadOnly().forEach(p -> sb.append("  ").append(p).append("\n"));
        sb.append("WRITE-ACTION PREFIXES:\n");
        commandRegistry.allWriteActions().forEach(p -> sb.append("  ").append(p).append("\n"));
        return printAndReturn(sb.toString());
    }

    private String execLocal(String command) {
        return switch (commandRegistry.classify(command)) {
            case READ_ONLY -> printAndReturn(format(bashService.execLocal(command)));
            case WRITE_ACTION -> {
                if (requestAndWait(null, "Локальная write-команда:\n" + command, false)
                        != ApprovalService.Decision.YES) yield "Отклонено оператором.";
                yield printAndReturn(format(bashService.execLocal(command)));
            }
            case UNKNOWN -> {
                var decision = requestAndWait(null,
                        "Локальная неизвестная команда — разрешить?\n" + command, true);
                yield switch (decision) {
                    case YES_ALWAYS -> {
                        commandRegistry.addReadOnlyPrefix(command);
                        yield printAndReturn(format(bashService.execLocal(command)));
                    }
                    case YES -> printAndReturn(format(bashService.execLocal(command)));
                    case NO -> "Отклонено оператором.";
                };
            }
        };
    }

    private static boolean isGitSourceOp(String command) {
        var stripped = command.stripLeading().replaceAll("^(sudo|timeout|nice)\\s+", "");
        return stripped.matches("(?s)(git\\s+(clone|pull|fetch).*|mkdir.*&&.*git\\s+(clone|pull).*)");
    }

    private static String sshWrap(Host host, String command) {
        return "ssh %s '%s'".formatted(host.sshTarget(), command.replace("'", "'\\''"));
    }

    private ApprovalService.Decision requestAndWait(String ignored, String desc, boolean canAlways) {
        var progressSnapshot = progressTracker.snapshot().orElse(null);
        progressService.forceUpdate("⏳ Жду подтверждения оператора...");
        var incidentHeader = investigationContext.get()
                .map(i -> "[%s] %s / %s\n\n".formatted(i.id(), i.hostId(), i.serviceId()))
                .orElse("");
        try {
            var decision = approvalService.requestApproval(null, incidentHeader + desc, canAlways)
                    .get(5, TimeUnit.MINUTES);
            progressTracker.restore(progressSnapshot);
            progressService.forceUpdate(switch (decision) {
                case YES, YES_ALWAYS -> "⏳ Подтверждение получено, продолжаю расследование...";
                case NO -> "⏳ Оператор отклонил команду, продолжаю расследование...";
            });
            return decision;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progressTracker.restore(progressSnapshot);
            return ApprovalService.Decision.NO;
        } catch (ExecutionException | TimeoutException e) {
            progressTracker.restore(progressSnapshot);
            return ApprovalService.Decision.NO;
        }
    }

    private static String format(com.vadim.devops.bash.BashResult r) {
        return "exit=%d\n%s%s".formatted(
                r.exitCode(),
                r.stdout().isBlank() ? "" : r.stdout(),
                r.stderr().isBlank() ? "" : "\nSTDERR: " + r.stderr());
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String printAndReturn(String result) {
        var preview = result.length() > 500 ? result.substring(0, 500) + "…" : result;
        log.debug("↩ {}", preview);
        return result;
    }
}
