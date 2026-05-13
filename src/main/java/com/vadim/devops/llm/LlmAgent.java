package com.vadim.devops.llm;

import com.vadim.devops.kb.KnowledgeBaseService;
import com.vadim.devops.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class LlmAgent {

    private static final Logger log = LoggerFactory.getLogger(LlmAgent.class);
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final ChatClient chatClient;
    private final KnowledgeBaseService kb;

    public LlmAgent(ChatClient.Builder chatClientBuilder,
                    KnowledgeBaseService kb, BashTool bashTool, InventoryTool inventoryTool,
                    IncidentTool incidentTool, SourceCodeTool sourceCodeTool,
                    Optional<WebSearchTool> webSearchTool,
                    CompactLoggingAdvisor loggingAdvisor) {
        this.kb = kb;
        var tools = new java.util.ArrayList<Object>(List.of(bashTool, inventoryTool, incidentTool, sourceCodeTool));
        webSearchTool.ifPresent(tools::add);
        this.chatClient = chatClientBuilder
                .defaultTools(tools.toArray())
                .defaultAdvisors(loggingAdvisor)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    public String ask(String question) {
        var history = loadHistory(null);
        var withQuestion = append(history, "user", question);
        kb.saveSession(withQuestion);

        log.debug("→ LLM ({} msgs in history)", history.size());
        var response = chatClient.prompt()
                .messages(toSpringMessages(history))
                .user(question)
                .call()
                .content();
        log.debug("← LLM ответил ({} chars)", response == null ? 0 : response.length());

        kb.saveSession(append(withQuestion, "assistant", response));
        return response;
    }

    public String askInIncidentContext(String incidentId, String question) {
        var history = loadHistory(incidentId);
        var withQuestion = append(history, "user", question);
        kb.saveConversation(incidentId, withQuestion);

        log.debug("→ LLM [incident={}] ({} msgs in history)", incidentId, history.size());
        log.debug("→ LLM [incident={}] prompt:\n{}", incidentId, question);
        var response = chatClient.prompt()
                .messages(toSpringMessages(history))
                .user(question)
                .call()
                .content();
        log.debug("← LLM ответил ({} chars)", response == null ? 0 : response.length());

        kb.saveConversation(incidentId, append(withQuestion, "assistant", response));
        return response;
    }

    private List<ConversationMessage> append(List<ConversationMessage> history, String role, String content) {
        var updated = new ArrayList<>(history);
        updated.add(new ConversationMessage(Instant.now(), role, content));
        return updated;
    }

    private List<ConversationMessage> loadHistory(String incidentId) {
        var full = incidentId != null
                ? kb.loadConversation(incidentId)
                : kb.loadTodaySession();
        if (full.size() <= MAX_HISTORY_MESSAGES) return full;
        return full.subList(full.size() - MAX_HISTORY_MESSAGES, full.size());
    }

    private static List<Message> toSpringMessages(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> switch (m.role()) {
                    case "user" -> (Message) new UserMessage(m.content());
                    case "assistant" -> new AssistantMessage(m.content());
                    default -> null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static final String SYSTEM_PROMPT = """
            Ты — DevOps-агент, который управляет Linux-хостами.

            Правила:
            - Перед работой вызови getInventory чтобы узнать список хостов и сервисов
            - Используй инструмент bash чтобы собирать факты (ssh на хосты, локальные команды)
            - В поле command передавай ПОЛНУЮ команду без ssh-префикса (например: "journalctl -u crm.service --since '1 hour ago' | grep -i error | tail -50")
            - Для чтения логов сервиса используй logsCommand из инвентори — там уже правильная команда. Если logsCommand не задан — используй journalctl -u <systemdUnit> или docker logs <containerName>
            - Для клонирования/обновления репозитория ВСЕГДА используй инструмент updateSourceCode(serviceId) — НИКОГДА не вызывай git clone, git pull, mkdir для подготовки директории или любые другие bash-команды для управления исходниками. updateSourceCode сам создаёт директорию и возвращает локальный путь.
            - Перед write-действием объясни что будешь делать и почему
            - Отвечай кратко и по делу, только факты
            - Если оператор явно говорит тебе сохранить что-то в инвентори — сохраняй сразу через saveHost/saveService
            - sourcesPath — путь к исходникам на ЛОКАЛЬНОЙ машине агента. Для поиска по sourcesPath вызывай bash с hostId=null
            - Никогда не ищи исходники (.java, .py, .ts) через SSH на удалённом сервере
            - Профайлинг CPU (asprof, py-spy) запускается АВТОМАТИЧЕСКИ до начала расследования — никогда не запускай их через bash самостоятельно. Результаты или причина неудачи будут переданы тебе в тексте задачи

            Работа с инцидентами:
            - При расследовании сначала вызови searchSimilarIncidents — возможно эта проблема уже решалась
            - Если нашёл похожий инцидент — изучи его через getIncident (там история расследования)
            - ОБЯЗАТЕЛЬНО изучи исходный код сервиса: найди sourcesPath в инвентори и прочитай ключевые файлы через bash (hostId=null). Без понимания кода нельзя найти настоящую причину
            - Считай причиной только то, что подтверждается ТЕКУЩИМ кодом в sourcesPath и текущими фактами инцидента
            - Если нашёл баг по памяти, похожему инциденту или старому описанию — ОБЯЗАТЕЛЬНО открой текущий файл и проверь, что дефект всё ещё существует в текущей версии исходников
            - Если в текущем коде дефект уже исправлен, НЕ указывай его как причину инцидента и НЕ включай в рекомендации как актуальный фикс
            - Такие находки явно помечай как исторические/уже исправленные и не относящиеся к текущей причине
            - Для каждой кодовой гипотезы приводи доказательство из текущего кода: файл, метод, условие, проверенная ветка логики
            - Не путай потенциальный дефект в коде и корневую причину инцидента: root cause должен одновременно подтверждаться текущим кодом, логами, метриками и симптомами
            - Запрещено включать в итоговый root cause баг, который не подтверждён текущим содержимым файла через bash/rg/sed
            - После определения причины — сохрани гипотезу через updateIncidentHypothesis
            - После устранения — закрой инцидент через resolveIncident с описанием что было сделано

            Инциденты нехватки ресурсов (высокий CPU, RAM, диск):
            - СНАЧАЛА определи какой процесс потребляет ресурс: ps aux --sort=-%cpu | head -10
            - Для Java: jstack <pid>, jmap -histo <pid> для диагностики потоков и памяти
            - Для БД: посмотри slow query log, активные запросы (SHOW PROCESSLIST, pg_stat_activity), индексы
            - Изучи исходный код сервиса (sourcesPath в инвентори) — найди причину в коде: утечки памяти, N+1 запросы, отсутствующие индексы, неоптимальные алгоритмы, бесконечные циклы
            - Сформулируй конкретные предложения по изменению кода: какой класс/метод переписать, какой индекс добавить, где кэшировать
            - Добавление ресурсов (RAM, CPU, диск) предлагай ТОЛЬКО если причина в объективном росте нагрузки и код уже оптимален

            Поиск в интернете (search):
            - При расследовании ВСЕГДА ищи в интернете если видишь: исключение, стек-трейс, код ошибки, OOM, crash
            - Ищи формулировку вида "<название ошибки> cause solution" или "<сервис> <ошибка> fix"
            - Ищи документацию если непонятно поведение системы, библиотеки или конфига
            - Не изобретай решение самостоятельно — сначала проверь есть ли известный фикс
            """;
}
