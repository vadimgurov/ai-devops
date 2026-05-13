package com.vadim.devops.telegram;

import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.llm.LlmAgent;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentFormatter;
import com.vadim.devops.monitoring.IncidentManager;
import com.vadim.devops.monitoring.ProfilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${devops.telegram.token:}')")
public class DevopsTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DevopsTelegramBot.class);

    private final String token;
    private final TelegramClient telegramClient;
    private final TelegramNotifier notifier;
    private final ApprovalService approvalService;
    private final LlmAgent llmAgent;
    private final InventoryLoader inventory;
    private final IncidentManager incidentManager;
    private final ProfilingService profilingService;
    private final KnowledgeBaseService kb;
    private final ProgressTracker progressTracker;
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "llm-worker"));

    private final String operatorChatId;

    public DevopsTelegramBot(DevopsProperties props, TelegramNotifier notifier,
                             ApprovalService approvalService, LlmAgent llmAgent,
                             InventoryLoader inventory, IncidentManager incidentManager,
                             ProfilingService profilingService,
                             KnowledgeBaseService kb, ProgressTracker progressTracker) {
        this.token = props.telegram().token();
        this.operatorChatId = props.telegram().operatorChatId();
        this.telegramClient = notifier.getTelegramClient();
        this.notifier = notifier;
        this.approvalService = approvalService;
        this.llmAgent = llmAgent;
        this.inventory = inventory;
        this.incidentManager = incidentManager;
        this.profilingService = profilingService;
        this.kb = kb;
        this.progressTracker = progressTracker;
    }

    @PostConstruct
    void registerCommands() {
        try {
            telegramClient.execute(SetMyCommands.builder()
                    .commands(List.of(
                            new BotCommand("hosts", "Список хостов"),
                            new BotCommand("incidents", "Открытые инциденты"),
                            new BotCommand("resolved", "Закрытые инциденты"),
                            new BotCommand("stop", "Остановить расследование"),
                            new BotCommand("clear", "Очистить историю сессии"),
                            new BotCommand("help", "Справка")))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("SetMyCommands failed: {}", e.getMessage());
        }
    }

    @Override
    public String getBotToken() { return token; }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() { return this; }

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(this::handle);
    }

    private void handle(Update update) {
        if (update.hasCallbackQuery()) {
            var callbackChatId = String.valueOf(update.getCallbackQuery().getMessage().getChatId());
            if (!operatorChatId.equals(callbackChatId)) return;
            handleCallback(update);
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            var msgChatId = String.valueOf(update.getMessage().getChatId());
            if (!operatorChatId.equals(msgChatId)) {
                log.warn("Ignoring message from unauthorized chat {}", msgChatId);
                return;
            }
            handleMessage(update);
        }
    }

    private void handleMessage(Update update) {
        var chatId = String.valueOf(update.getMessage().getChatId());
        var from = update.getMessage().getFrom();
        var user = from != null ? "@" + from.getUserName() + " (" + from.getFirstName() + ")" : "unknown";
        var text = update.getMessage().getText().trim();

        log.info("Telegram [{}]: {}", user, text);

        switch (text) {
            case "/stop" -> {
                if (incidentManager.cancelInvestigation()) {
                    sendReply(chatId, "⛔ Расследование остановлено.");
                } else {
                    sendReply(chatId, "Нет активного расследования.");
                }
            }
            case "/clear" -> {
                kb.clearTodaySession();
                sendReply(chatId, "✅ История сессии очищена.");
            }
            case "/hosts" -> sendHostList(chatId);
            case "/incidents" -> sendIncidentList(chatId);
            case "/resolved" -> sendResolvedList(chatId);
            case "/help", "/start" -> sendReply(chatId, """
                    *DevOps Agent*

                    Команды:
                    /hosts — список хостов
                    /incidents — открытые инциденты
                    /resolved — закрытые инциденты
                    /clear — очистить историю сессии

                    Или просто задай вопрос текстом.
                    """);
            default -> {
                var progressMsgId = notifier.sendAndGetId(chatId, "⏳ Думаю...");
                CompletableFuture.supplyAsync(() -> {
                            if (progressMsgId != null) progressTracker.start(chatId, progressMsgId);
                            try {
                                return llmAgent.ask(text);
                            } finally {
                                progressTracker.clear();
                                if (progressMsgId != null) notifier.deleteMessage(chatId, progressMsgId);
                            }
                        }, llmExecutor)
                        .thenAccept(response -> sendHtml(chatId, TelegramMarkdownConverter.convert(response)))
                        .exceptionally(e -> {
                            if (!isCausedByInterrupt(e)) sendReply(chatId, "Ошибка: " + e.getMessage());
                            return null;
                        });
            }
        }
    }

    private static final int MAX_KEYBOARD_BUTTONS = 100;

    private void sendHostList(String chatId) {
        var hosts = new ArrayList<>(inventory.allHosts());
        if (hosts.isEmpty()) {
            sendReply(chatId, "Хостов в инвентори нет.");
            return;
        }
        var limited = hosts.size() > MAX_KEYBOARD_BUTTONS ? hosts.subList(0, MAX_KEYBOARD_BUTTONS) : hosts;
        var rows = limited.stream()
                .map(h -> new InlineKeyboardRow(
                        btn("🖥 " + h.id() + " (" + h.env() + ")", "host:" + h.id())))
                .toList();
        var text = "Выбери хост:" + (hosts.size() > MAX_KEYBOARD_BUTTONS
                ? "\n_(показаны первые " + MAX_KEYBOARD_BUTTONS + " из " + hosts.size() + ")_" : "");
        sendWithKeyboard(chatId, text, rows);
    }

    private void sendIncidentList(String chatId) {
        var incidents = new ArrayList<>(incidentManager.openIncidents());
        if (incidents.isEmpty()) {
            sendReply(chatId, "✅ Открытых инцидентов нет.");
            return;
        }
        var limited = incidents.size() > MAX_KEYBOARD_BUTTONS
                ? incidents.subList(0, MAX_KEYBOARD_BUTTONS) : incidents;
        var sb = new StringBuilder("🚨 <b>Инциденты:</b>\n\n");
        var rows = new ArrayList<InlineKeyboardRow>();
        for (var inc : limited) {
            var statusEmoji = switch (inc.status()) {
                case PROFILING    -> "⏳";
                case OPEN         -> "🟠";
                case INVESTIGATING -> "🔄";
                default           -> "❓";
            };
            sb.append(statusEmoji).append(" <code>").append(inc.id()).append("</code>")
              .append(" — <code>").append(inc.hostId()).append("</code>")
              .append(inc.serviceId() != null ? "/<code>" + inc.serviceId() + "</code>" : "")
              .append(" [").append(inc.status()).append("]\n")
              .append(inc.summary()).append("\n\n");
            var actionBtn = switch (inc.status()) {
                case PROFILING     -> btn("⏭ Пропустить профайлинг", "skip_profiling:" + inc.id());
                case OPEN          -> btn("🔍 Расследовать", "investigate:" + inc.id());
                case INVESTIGATING  -> btn("✅ Закрыть", "resolve_incident:" + inc.id());
                default            -> null;
            };
            if (actionBtn != null) rows.add(new InlineKeyboardRow(actionBtn));
        }
        if (incidents.size() > MAX_KEYBOARD_BUTTONS)
            sb.append("_(и ещё ").append(incidents.size() - MAX_KEYBOARD_BUTTONS).append(" инцидентов)_");
        sendWithKeyboard(chatId, sb.toString(), rows);
    }

    private static final int RESOLVED_PAGE_SIZE = 20;

    private void sendResolvedList(String chatId) {
        var resolved = kb.findResolvedIncidents();
        if (resolved.isEmpty()) {
            sendReply(chatId, "Закрытых инцидентов нет.");
            return;
        }
        var page = resolved.size() > RESOLVED_PAGE_SIZE ? resolved.subList(0, RESOLVED_PAGE_SIZE) : resolved;
        var sb = new StringBuilder("✅ <b>Закрытые инциденты</b> (последние " + page.size() + "):\n\n");
        var rows = new ArrayList<InlineKeyboardRow>();
        for (var inc : page) {
            var recurrences = inc.events() == null ? 0L
                    : inc.events().stream().filter(e -> "recurrence".equals(e.eventType())).count();
            sb.append("✅ <code>").append(inc.id()).append("</code>")
              .append(" — <code>").append(inc.hostId()).append("</code>")
              .append(inc.serviceId() != null ? "/<code>" + inc.serviceId() + "</code>" : "")
              .append("\n").append(inc.summary());
            if (recurrences > 0) sb.append(" <i>(повторился ×").append(recurrences).append(")</i>");
            sb.append("\n\n");
            rows.add(new InlineKeyboardRow(btn("📋 " + inc.id(), "view_incident:" + inc.id())));
        }
        if (resolved.size() > RESOLVED_PAGE_SIZE)
            sb.append("_(и ещё ").append(resolved.size() - RESOLVED_PAGE_SIZE).append(" инцидентов)_");
        sendWithKeyboard(chatId, sb.toString(), rows);
    }

    private void handleCallback(Update update) {
        var query = update.getCallbackQuery();
        var data = query.getData();
        var callbackId = query.getId();
        var chatId = String.valueOf(query.getMessage().getChatId());

        if (data.startsWith("host:")) {
            answerCallback(callbackId, null);
            sendHostDetails(chatId, data.substring(5));

        } else if (data.startsWith("service:")) {
            answerCallback(callbackId, null);
            var parts = data.substring(8).split(":", 2);
            sendServiceDetails(chatId, parts[0], parts[1]);

        } else if (data.startsWith("investigate:")) {
            var incidentId = data.substring(12);
            if (!incidentManager.investigateNow(incidentId)) {
                var runningId = incidentManager.activeIncidentId();
                answerCallback(callbackId, "Расследование уже идёт");
                var runningIncidentText = runningId == null ? null : incidentManager.loadIncident(runningId)
                        .map(IncidentFormatter::htmlRef)
                        .orElse("<code>" + IncidentFormatter.escapeHtml(runningId) + "</code>");
                sendWithKeyboard(chatId,
                        runningIncidentText != null
                                ? "⚠️ Уже идёт расследование " + runningIncidentText + ". Остановить?"
                                : "⚠️ Уже идёт расследование. Остановить?",
                        List.of(new InlineKeyboardRow(btn("⛔ Остановить", "stop_investigation"))));
            } else {
                answerCallback(callbackId, "Начинаю расследование...");
                sendReply(chatId, incidentManager.loadIncident(incidentId)
                        .map(incident -> "🔍 Расследую " + IncidentFormatter.htmlRef(incident) + "...")
                        .orElse("🔍 Расследую <code>" + incidentId + "</code>..."));
            }
        } else if (data.equals("stop_investigation")) {
            var stopped = incidentManager.cancelInvestigation();
            answerCallback(callbackId, stopped ? "⛔ Остановлено" : "Нет активного расследования");

        } else if (data.startsWith("skip_profiling:")) {
            var incidentId = data.substring(15);
            answerCallback(callbackId, null);
            if (profilingService.skipProfiling(incidentId)) {
                sendReply(chatId, incidentManager.loadIncident(incidentId)
                        .map(incident -> "⏭ Профайлинг пропущен для " + IncidentFormatter.htmlRef(incident)
                                + ", инцидент открыт.")
                        .orElse("⏭ Профайлинг <code>" + incidentId + "</code> пропущен, инцидент открыт."));
            } else {
                sendReply(chatId, incidentManager.loadIncident(incidentId)
                        .map(incident -> "⚠️ Инцидент не в статусе PROFILING: " + IncidentFormatter.htmlRef(incident))
                        .orElse("⚠️ Инцидент не в статусе PROFILING: <code>" + incidentId + "</code>"));
            }

        } else if (data.startsWith("resolve_incident:")) {
            var incidentId = data.substring(17);
            answerCallback(callbackId, null);
            if (incidentManager.forceResolve(incidentId, "Закрыт оператором")) {
                sendReply(chatId, incidentManager.loadIncident(incidentId)
                        .map(incident -> "✅ Инцидент закрыт: " + IncidentFormatter.htmlRef(incident))
                        .orElse("✅ Инцидент <code>" + incidentId + "</code> закрыт."));
            } else {
                sendReply(chatId, incidentManager.loadIncident(incidentId)
                        .map(incident -> "⚠️ Не удалось закрыть инцидент: " + IncidentFormatter.htmlRef(incident))
                        .orElse("⚠️ Не удалось закрыть инцидент: <code>" + incidentId + "</code>"));
            }

        } else if (data.startsWith("view_incident:")) {
            var incidentId = data.substring(14);
            answerCallback(callbackId, null);
            sendReply(chatId, incidentManager.loadIncident(incidentId)
                    .map(i -> {
                        var sb = new StringBuilder();
                        sb.append("✅ <b>").append(IncidentFormatter.escapeHtml(i.id())).append("</b>\n");
                        sb.append("Хост: <code>").append(i.hostId()).append("</code>");
                        if (i.serviceId() != null) sb.append(" / <code>").append(i.serviceId()).append("</code>");
                        sb.append("\n").append(IncidentFormatter.escapeHtml(i.summary())).append("\n");
                        if (i.rootCauseHypothesis() != null)
                            sb.append("\n<b>Причина:</b>\n").append(TelegramMarkdownConverter.convert(i.rootCauseHypothesis()));
                        if (i.events() != null) {
                            var recurrences = i.events().stream().filter(e -> "recurrence".equals(e.eventType())).toList();
                            if (!recurrences.isEmpty())
                                sb.append("\n<b>Повторился:</b> ×").append(recurrences.size());
                        }
                        return sb.toString();
                    })
                    .orElse("Инцидент не найден: <code>" + incidentId + "</code>"));

        } else {
            // Approval callbacks
            ApprovalService.Decision decision = null;
            String approvalId = null;
            if (data.startsWith("approve:")) { approvalId = data.substring(8); decision = ApprovalService.Decision.YES; }
            else if (data.startsWith("always:")) { approvalId = data.substring(7); decision = ApprovalService.Decision.YES_ALWAYS; }
            else if (data.startsWith("deny:")) { approvalId = data.substring(5); decision = ApprovalService.Decision.NO; }

            if (approvalId != null) {
                var resolved = approvalService.resolve(approvalId, decision);
                answerCallback(callbackId, resolved ? "✅ " + decision : "⚠️ Уже обработано");
                notifier.deleteMessage(chatId, query.getMessage().getMessageId());
            }
        }
    }

    private void sendHostDetails(String chatId, String hostId) {
        var hostOpt = inventory.findHost(hostId);
        if (hostOpt.isEmpty()) {
            sendReply(chatId, "Хост не найден: " + hostId);
            return;
        }
        var host = hostOpt.get();
        var sb = new StringBuilder("🖥 <b>").append(host.id()).append("</b>\n");
        sb.append("SSH: <code>").append(host.sshTarget()).append("</code>\n");
        sb.append("Env: ").append(host.env()).append("\n");
        if (host.notes() != null && !host.notes().isBlank())
            sb.append("Заметки: ").append(host.notes()).append("\n");
        sb.append("\n<b>Сервисы:</b>\n");

        var rows = new ArrayList<InlineKeyboardRow>();
        if (host.services() == null || host.services().isEmpty()) {
            sb.append("— нет сервисов\n");
        } else {
            for (var s : host.services()) {
                var hasIncident = incidentManager.hasOpenIncident(hostId, s.id());
                sb.append(hasIncident ? "🔴 " : "🟢 ")
                  .append("<code>").append(s.id()).append("</code>")
                  .append(" (").append(s.runtime()).append(")\n");
                rows.add(new InlineKeyboardRow(
                        btn((hasIncident ? "🔴 " : "🟢 ") + s.id(), "service:" + hostId + ":" + s.id())));
            }
        }

        if (host.alertTypes() != null && !host.alertTypes().isEmpty())
            sb.append("\n⚠️ Фильтр алертов: ").append(host.alertTypes()).append("\n");

        if (host.telemetry() != null && !host.telemetry().isEmpty()) {
            sb.append("\n<b>Телеметрия:</b>\n");
            for (var t : host.telemetry()) {
                sb.append("• ").append(t.name())
                  .append(" threshold=").append(t.threshold());
                if (t.minDurationMs() != null)
                    sb.append(" minDuration=").append(t.minDurationMs() / 1000).append("s");
                sb.append("\n  <code>").append(t.command()).append("</code>\n");
            }
        }

        if (rows.isEmpty()) sendReply(chatId, sb.toString());
        else sendWithKeyboard(chatId, sb.toString(), rows.subList(0, Math.min(rows.size(), MAX_KEYBOARD_BUTTONS)));
    }

    private void sendServiceDetails(String chatId, String hostId, String serviceId) {
        var serviceOpt = inventory.findService(hostId, serviceId);
        if (serviceOpt.isEmpty()) {
            sendReply(chatId, "Сервис не найден: " + serviceId);
            return;
        }
        var s = serviceOpt.get();
        var hasIncident = incidentManager.hasOpenIncident(hostId, serviceId);
        var sb = new StringBuilder(hasIncident ? "🔴 " : "🟢 ");
        sb.append("<b>").append(s.id()).append("</b>");
        if (s.name() != null && !s.name().equals(s.id())) sb.append(" — ").append(s.name());
        sb.append(" (").append(s.runtime()).append(")\n");
        if (s.systemdUnit() != null) sb.append("Unit: <code>").append(s.systemdUnit()).append("</code>\n");
        if (s.containerName() != null) sb.append("Container: <code>").append(s.containerName()).append("</code>\n");
        if (s.healthCheck() != null) {
            sb.append("Health: <code>").append(s.healthCheck()).append("</code>");
            if (s.healthCheckMinDurationMs() != null)
                sb.append(" (grace ").append(s.healthCheckMinDurationMs() / 1000).append("s)");
            sb.append("\n");
        }
        if (s.logsCommand() != null) sb.append("Logs: <code>").append(s.logsCommand()).append("</code>\n");
        if (s.versionUrl() != null) sb.append("VersionUrl: <code>").append(s.versionUrl()).append("</code>\n");
        if (s.repoUrl() != null) sb.append("Repo: <code>").append(s.repoUrl()).append("</code>\n");
        if (s.sourcesPath() != null) sb.append("Sources: <code>").append(s.sourcesPath()).append("</code>\n");
        if (s.configFiles() != null && !s.configFiles().isEmpty())
            sb.append("Configs: ").append(String.join(", ", s.configFiles())).append("\n");
        if (s.allowedActions() != null && !s.allowedActions().isEmpty())
            sb.append("AllowedActions: ").append(s.allowedActions()).append("\n");

        var rows = new ArrayList<InlineKeyboardRow>();
        if (hasIncident) {
            incidentManager.openIncidents().stream()
                    .filter(i -> serviceId.equals(i.serviceId()) && hostId.equals(i.hostId()))
                    .forEach(i -> rows.add(new InlineKeyboardRow(
                            btn("🔍 Расследовать " + i.id(), "investigate:" + i.id()))));
        }

        if (rows.isEmpty()) sendReply(chatId, sb.toString());
        else sendWithKeyboard(chatId, sb.toString(), rows);
    }

    private void sendReply(String chatId, String text) {
        sendHtml(chatId, text);
    }

    private static final int MAX_MESSAGE_LENGTH = 4096;

    private void sendHtml(String chatId, String html) {
        if (html == null || html.isBlank()) return;
        if (html.length() <= MAX_MESSAGE_LENGTH) {
            sendHtmlChunk(chatId, html);
            return;
        }
        // Split on newlines to avoid cutting mid-tag
        var parts = splitSafely(html, MAX_MESSAGE_LENGTH);
        parts.forEach(part -> sendHtmlChunk(chatId, part));
    }

    private void sendHtmlChunk(String chatId, String html) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(html)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram reply failed: {}", e.getMessage());
        }
    }

    private static List<String> splitSafely(String text, int maxLen) {
        var result = new ArrayList<String>();
        var remaining = text;
        while (remaining.length() > maxLen) {
            int splitAt = remaining.lastIndexOf('\n', maxLen);
            if (splitAt <= 0) splitAt = maxLen;
            result.add(remaining.substring(0, splitAt));
            remaining = remaining.substring(splitAt).stripLeading();
        }
        if (!remaining.isBlank()) result.add(remaining);
        return result;
    }

    private void sendWithKeyboard(String chatId, String text, List<InlineKeyboardRow> rows) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(new InlineKeyboardMarkup(rows))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram sendWithKeyboard failed: {}", e.getMessage());
        }
    }

    private void answerCallback(String callbackId, String text) {
        try {
            var builder = AnswerCallbackQuery.builder().callbackQueryId(callbackId);
            if (text != null) builder.text(text);
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.warn("AnswerCallbackQuery failed: {}", e.getMessage());
        }
    }

    private static InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private static boolean isCausedByInterrupt(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }
}
