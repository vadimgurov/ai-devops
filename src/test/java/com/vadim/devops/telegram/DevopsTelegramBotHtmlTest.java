package com.vadim.devops.telegram;

import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.kb.InventoryLoader;
import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.llm.LlmAgent;
import com.vadim.devops.model.Host;
import com.vadim.devops.model.ServiceConfig;
import com.vadim.devops.model.TelemetryCheck;
import com.vadim.devops.monitoring.IncidentManager;
import com.vadim.devops.monitoring.ProfilingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Всё, что приходит из инвентори, уезжает в Telegram с parseMode=HTML —
 * значит должно быть экранировано, иначе Telegram отвечает 400 и сообщение просто не доходит.
 */
class DevopsTelegramBotHtmlTest {

    /** Реальная телеметрия crm-prod: python-выражение с '<' роняло парсер Telegram. */
    private static final String PYTHON_CMD = "python3 -c 'past=set(s for t,s in rows if t<now-win)'";

    private TelegramClient telegramClient;
    private InventoryLoader inventory;
    private IncidentManager incidentManager;
    private DevopsTelegramBot bot;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        inventory = mock(InventoryLoader.class);
        incidentManager = mock(IncidentManager.class);

        var notifier = mock(TelegramNotifier.class);
        when(notifier.getTelegramClient()).thenReturn(telegramClient);

        var props = new DevopsProperties(null, null, null, null,
                new DevopsProperties.TelegramProperties("token", "42"), null);

        bot = new DevopsTelegramBot(props, notifier, mock(ApprovalService.class), mock(LlmAgent.class),
                inventory, incidentManager, mock(ProfilingService.class),
                mock(KnowledgeBaseService.class), mock(ProgressTracker.class));
    }

    @Test
    void hostDetailsEscapeAngleBracketsInTelemetryCommand() throws Exception {
        var telemetry = new TelemetryCheck("client-new-errors", PYTHON_CMD, 1.0, 0L);
        when(inventory.findHost("crm-prod")).thenReturn(Optional.of(
                new Host("crm-prod", "crm", "prod", "1.2.3.4", "ubuntu@crm", "заметки <b> & прочее",
                        List.of(), List.of(telemetry), List.of())));

        bot.sendHostDetails("42", "crm-prod");

        var text = captureSentText();
        assertThat(text).contains("t&lt;now-win)")
                .contains("&amp;")
                .doesNotContain("<now-win")
                .doesNotContain("заметки <b>");
    }

    @Test
    void serviceDetailsEscapeAngleBracketsInCommands() throws Exception {
        var service = new ServiceConfig("kb", "kb", "crm-prod", "java", "bin-kb-1", null,
                "docker inspect bin-kb-1 --format='{{.State.Running}}' | grep -q true",
                null, null, null, "docker logs bin-kb-1 2>&1 | grep '<error>'", 120000L, List.of());
        when(inventory.findService("crm-prod", "kb")).thenReturn(Optional.of(service));
        when(incidentManager.hasOpenIncident("crm-prod", "kb")).thenReturn(false);

        bot.sendServiceDetails("42", "crm-prod", "kb");

        var text = captureSentText();
        assertThat(text).contains("&lt;error&gt;").doesNotContain("<error>");
    }

    @Test
    void hostDetailsKeepTelegramTagsIntact() throws Exception {
        when(inventory.findHost("h")).thenReturn(Optional.of(
                new Host("h", "h", "prod", "1.2.3.4", "ubuntu@h", null, List.of(), List.of(), List.of())));

        bot.sendHostDetails("42", "h");

        assertThat(captureSentText()).contains("<b>h</b>").contains("<code>ubuntu@h</code>");
    }

    private String captureSentText() throws Exception {
        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, atLeastOnce()).execute(captor.capture());
        return captor.getValue().getText();
    }
}
