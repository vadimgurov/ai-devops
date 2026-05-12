package com.vadim.devops.telegram;

import com.vadim.devops.config.DevopsProperties;
import com.vadim.devops.model.Incident;
import com.vadim.devops.model.IncidentFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${devops.telegram.token:}')")
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramClient client;
    private final String chatId;

    public TelegramNotifier(DevopsProperties props) {
        this.client = new OkHttpTelegramClient(props.telegram().token());
        this.chatId = props.telegram().operatorChatId();
    }

    public void sendMessage(String text) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram sendMessage failed: {}", e.getMessage());
        }
    }

    public Integer sendAndGetId(String chatId, String text) {
        try {
            var msg = client.execute(SendMessage.builder()
                    .chatId(chatId).text(text).parseMode("HTML").build());
            return msg.getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Telegram send failed: {}", e.getMessage());
            return null;
        }
    }

    public void editMessage(String chatId, int messageId, String html) {
        try {
            client.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(truncateLines(html, 10))
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram editMessage failed: {}", e.getMessage());
        }
    }

    public void deleteMessage(String chatId, int messageId) {
        try {
            client.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram deleteMessage failed: {}", e.getMessage());
        }
    }

    public void sendIncidentAlert(Incident incident) {
        var emoji = switch (incident.severity()) {
            case CRITICAL -> "🔴";
            case HIGH     -> "🟠";
            case MEDIUM   -> "🟡";
            case LOW      -> "🔵";
        };
        sendMessage("%s <b>Инцидент</b> %s"
                .formatted(emoji, IncidentFormatter.htmlRef(incident)));
    }

    public void sendIncidentResolved(Incident incident) {
        sendMessage("✅ <b>Resolved</b> " + IncidentFormatter.htmlRef(incident));
    }

    public void sendApprovalRequest(String approvalId, String description, boolean canPermanentlyAllow) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            var rows = canPermanentlyAllow
                    ? List.of(new InlineKeyboardRow(
                            btn("✅ Да", "approve:" + approvalId),
                            btn("🔄 Всегда", "always:" + approvalId),
                            btn("❌ Нет", "deny:" + approvalId)))
                    : List.of(new InlineKeyboardRow(
                            btn("✅ Да", "approve:" + approvalId),
                            btn("❌ Нет", "deny:" + approvalId)));

            client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ <b>Требуется подтверждение</b>\n\n" + escapeHtml(description))
                    .parseMode("HTML")
                    .replyMarkup(new InlineKeyboardMarkup(rows))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram approval request failed: {}", e.getMessage());
        }
    }

    public String getOperatorChatId() { return chatId; }

    public TelegramClient getTelegramClient() { return client; }

    private static InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private static String truncateLines(String text, int max) {
        if (text == null) return "";
        var lines = text.split("\n", max + 1);
        if (lines.length <= max) return text;
        return String.join("\n", Arrays.copyOf(lines, max)) + "\n…";
    }

    private static String escapeHtml(String text) {
        return IncidentFormatter.escapeHtml(text);
    }
}
