# DevOps Agent — текущее состояние MVP

> Актуально на 2026-06-04. Документ описывает фактически реализованное
> поведение. Незавершённые и проблемные части вынесены в раздел 11.

## 1. Назначение и границы продукта

DevOps Agent — центральный сервис без агентов на целевых хостах. Он:

- выполняет заданные health check и telemetry-команды на Linux-хостах по SSH;
- ищет признаки исключений в journald и логах Docker-контейнеров;
- создаёт файловые инциденты и последовательно передаёт их LLM на расследование;
- даёт LLM инструменты для SSH-диагностики, чтения инвентори, истории инцидентов,
  локальных исходников и опционального веб-поиска;
- запрашивает подтверждение потенциально изменяющих систему команд через Telegram;
- позволяет оператору общаться с агентом и управлять инцидентами через Telegram;
- сохраняет инвентори, инциденты и историю разговоров в файлах на диске.

Это не полноценная система мониторинга: агент не хранит временные ряды, не строит
дашборды, не имеет SLA/SLO-модели и не рассчитан на несколько экземпляров или
большое количество хостов.

## 2. Архитектура

- **Central Agent:** Java 21, Spring Boot 3.4.4, non-web приложение.
- **LLM:** Spring AI 1.1.4; DeepSeek или OpenAI-compatible provider.
- **Удалённое выполнение:** системные `bash` и `ssh` через `ProcessBuilder`.
- **Хранилище:** JSON/YAML-файлы в `kb/`, без БД.
- **Операторский интерфейс:** Telegram Bot API long polling.
- **Планировщик:** Spring Scheduler, pool из 4 потоков.
- **Деплой:** Docker Compose; сборка Gradle с BuildKit cache.

Основной поток:

```text
scheduled checks -> anomaly -> incident file -> profiling (только CPU)
                 -> single-thread investigation queue -> LLM tools
                 -> Telegram progress/approval/result -> resolved incident
```

## 3. Инвентори и файловая база знаний

```text
kb/
  allowed_commands.yaml
  hosts/
    <host-id>.yaml
    <host-id>/services/<service-id>.yaml
  incidents/
    <incident-id>/incident.json
    <incident-id>/conversation.json
  conversations/
    session-<local-date>.json
```

Инвентори загружается в память при старте. Изменения, сделанные через LLM tools,
сразу записываются в YAML и обновляют in-memory представление. Ручные изменения
YAML во время работы автоматически не перечитываются.

Каноническое место хранения сервисов — отдельные файлы в
`hosts/<host-id>/services/`. Поле `services` может присутствовать в host YAML, но
при загрузке заменяется содержимым директории сервисов. Неизвестные и устаревшие
поля YAML/JSON игнорируются.

### Модель данных

**Host**

`id, name, env, ip, sshTarget, notes, services[], telemetry[], alertTypes[]`

**ServiceConfig**

`id, name, hostId, runtime, systemdUnit, containerName, healthCheck, versionUrl,`
`sourcesPath, repoUrl, logsCommand, healthCheckMinDurationMs, configFiles[]`

**TelemetryCheck**

`name, command, threshold, minDurationMs`

Команда телеметрии должна вывести одно число. Нарушением считается
`value >= threshold`.

**Incident**

`id, hostId, serviceId, status, severity, startedAt, resolvedAt, summary,`
`rootCauseHypothesis, confidence, events[]`

Статусы: `PROFILING`, `OPEN`, `INVESTIGATING`, `RESOLVED`.

Фактически используемые типы событий:

- `recurrence`;
- `hypothesis_updated`;
- `resolved`;
- `duplicate_closed`;
- `metric_recovered`;
- `profiling_complete`;
- `profiling_skipped`.

## 4. Мониторинг и детектирование

### Health checks

`MonitoringScheduler.cheapCheck()` по умолчанию запускается каждые 20 секунд.
Для каждого сервиса с `healthCheck` команда выполняется по SSH:

- exit code `0` означает healthy;
- ненулевой exit code означает failure;
- `healthCheckMinDurationMs` задаёт grace period непрерывного failure;
- состояние grace period хранится только в памяти процесса.

Health check создаёт `HEALTH_FAIL` только при детектированном переходе
healthy -> unhealthy. Восстановление создаёт `HEALTH_RECOVERED`.

### Телеметрия

`MonitoringScheduler.slowCheck()` по умолчанию запускается каждые 60 секунд.
Каждая настроенная команда выполняется по SSH последовательно внутри хоста:

- stdout целиком преобразуется в `double`;
- `minDurationMs` задерживает создание инцидента при непрерывном превышении;
- возврат ниже порога создаёт `METRIC_RECOVERED`;
- состояние превышения и таймеры хранятся только в памяти процесса.

### Поиск исключений

`ExceptionScanner` запускается каждые 120 секунд с initial delay 60 секунд.
Для каждого сервиса он читает новые логи:

- из journald, если задан `systemdUnit`;
- из Docker logs, если задан `containerName`;
- `logsCommand` при автоматическом сканировании не используется.

Сканер ищет строки с `Exception`, `Traceback`, `FATAL`, `PANIC` или `panic:`,
отбрасывает часть известных шумных строк и берёт до 5 совпадений. Несмотря на
название `EXCEPTION_BURST`, количественного порога burst сейчас нет.

Fingerprint строится преимущественно по найденному классу исключения и тексту
строки, обрезается до 120 символов. Одинаковый fingerprint не репортится повторно
до появления другого fingerprint для этого сервиса.

### Типы аномалий и фактическая реакция

| Тип | Фактическое поведение |
|---|---|
| `HEALTH_FAIL` | Создаёт HIGH-инцидент для сервиса и ставит его в очередь расследования |
| `METRIC_HIGH` | Создаёт MEDIUM-инцидент; если имя проверки равно `cpu`, сначала запускает профилирование |
| `EXCEPTION_BURST` | Создаёт HIGH-инцидент или регистрирует recurrence уже закрытого инцидента |
| `HEALTH_RECOVERED`, `METRIC_RECOVERED`, `SERVICE_RECOVERED` | Закрывает ещё не начатый OPEN-инцидент; во время INVESTIGATING только добавляет событие |
| `SERVICE_DOWN`, `SERVICE_RECOVERED` | Типы и detector существуют, но scheduler их не вызывает, а `SERVICE_DOWN` не обрабатывается `IncidentManager` |

`alertTypes` — точный allowlist имён аномалий на уровне хоста. Фильтр применяется
ко всем событиям, включая `*_RECOVERED`.

## 5. Жизненный цикл инцидента

Для одной пары `hostId/serviceId` новый инцидент не создаётся, пока существует
любой незакрытый. Для телеметрии роль `serviceId` выполняет имя проверки, например
`cpu` или `disk`.

Очередь расследований:

- одновременно выполняется одно автоматическое расследование;
- каждые 30 секунд выбирается самый новый инцидент в статусе `OPEN`;
- при выборе более старые OPEN-дубли той же пары host/service закрываются как
  `duplicate_closed`;
- перед вызовом LLM статус меняется на `INVESTIGATING`;
- если LLM не закрыл инцидент, он возвращается в `OPEN`;
- после рестарта зависшие `INVESTIGATING` возвращаются в `OPEN`;
- конкретный OPEN-инцидент можно запустить вручную кнопкой в Telegram.

Повтор закрытого exception-инцидента определяется по классу исключения,
`hostId` и `serviceId`. В старый инцидент добавляется событие `recurrence`,
оператор получает уведомление, новое расследование не запускается.

Похожие инциденты ищутся только среди `RESOLVED` того же `serviceId` простым
поиском любого ключевого слова в summary и hypothesis.

## 6. LLM-агент и инструменты

`LlmAgent` использует Spring AI `ChatClient` с системным промптом и typed tools.
Промпт требует при расследовании:

1. найти похожие инциденты;
2. изучить сохранённую историю;
3. обновить и проверить текущий исходный код, если задан `repoUrl`;
4. собрать факты через bash;
5. при необходимости использовать веб-поиск;
6. сохранить гипотезу;
7. закрыть инцидент итоговым выводом.

Поддерживаемые провайдеры:

- `LLM_PROVIDER=deepseek` — DeepSeek starter, модель `deepseek-v4-pro`;
- `LLM_PROVIDER=openai` — OpenAI-compatible endpoint через
  `OPENAI_BASE_URL` и `OPENAI_MODEL`.

История свободного диалога хранится по локальной дате, история расследования —
в директории инцидента. Перед запросом история ограничивается последними
20 сообщениями и максимум 300 000 символами.

### Доступные tools

| Инструмент | Фактическое назначение |
|---|---|
| `bash(hostId, command)` | Локальная или SSH-команда с классификацией и approval flow; ответ обрезается до 8 000 символов |
| `listCommands()` | Список read-only и write-action префиксов |
| `getInventory()` | Текущее in-memory инвентори |
| `saveHost(...)` | Upsert хоста; сохраняет существующие services, telemetry и alertTypes |
| `setHostAlertTypes(...)` | Задаёт точный allowlist типов аномалий |
| `saveTelemetryCheck(...)` | Upsert проверки с дедупликацией по имени или команде |
| `saveService(...)` | Записывает сервис из доступных tool-параметров |
| `listOpenIncidents()` | Все незакрытые инциденты |
| `searchSimilarIncidents(...)` | Простой файловый поиск похожих закрытых инцидентов |
| `getIncident(...)` | Метаданные, события и сокращённая история расследования |
| `updateIncidentHypothesis(...)` | Гипотеза и confidence |
| `resolveIncident(...)` | Закрытие инцидента из расследования |
| `updateSourceCode(serviceId)` | Локальный `git clone` или `git pull --ff-only`, сохранение `sourcesPath` |
| `search(query)` | Top-5 результатов Tavily; tool создаётся только при наличии API key |

`saveService` сейчас не принимает `containerName`, `versionUrl` и `sourcesPath` и
не merge-ит существующий сервис целиком. Повторный вызов может обнулить поля,
которых нет в сигнатуре tool.

`updateSourceCode` хранит репозитории в `./repos` по имени репозитория. Сервисы из
одной монорепы используют один локальный clone. Git через `bash` для операций
`clone`, `pull` и `fetch` блокируется.

## 7. Выполнение команд и approval flow

`CommandRegistry` классифицирует полную shell-команду, включая chains,
pipelines, wrappers и command substitutions:

- `READ_ONLY` выполняется сразу;
- `WRITE_ACTION` требует одноразового подтверждения;
- `UNKNOWN` требует подтверждения и позволяет выбрать «Всегда»;
- «Всегда» добавляет канонический префикс команды в `allowed_commands.yaml`
  как read-only.

Часть read-only команд захардкожена, остальные read-only/write-action/filter
prefixes хранятся в YAML. Явные write-action prefixes проверяются раньше
read-only. `sed -i` отдельно считается write-action.

Approval отправляется единственному настроенному Telegram-оператору. Варианты:
`Да`, `Всегда` для UNKNOWN и `Нет`. Ожидание в `BashTool` и при установке
профайлеров ограничено 5 минутами; timeout трактуется как отказ.

Автоматические monitoring-команды, exception scan и встроенные profiling-команды
выполняются напрямую через `BashRunner`, без `CommandRegistry`. Установка
`asprof` или `py-spy` отдельно требует подтверждения.

Стандартный timeout bash/SSH-команды — 30 секунд. Для профилирующей команды
используется отдельный timeout, по умолчанию 90 секунд.

## 8. CPU-профилирование

Профилирование запускается только для `METRIC_HIGH`, если имя telemetry check
равно `cpu` без учёта регистра. Инцидент сначала получает статус `PROFILING`.

`ProfilingService`:

- получает `ps aux --sort=-%cpu | head -10`;
- пытается сопоставить верхний процесс сервису;
- затем пробует собрать данные для всех сервисов хоста с точно поддерживаемым
  runtime, начиная с найденного кандидата;
- сохраняет результат в `profiling_complete` или причину в `profiling_skipped`;
- переводит инцидент в `OPEN`, после чего начинается LLM-расследование;
- возобновляет PROFILING-инциденты после рестарта и периодически проверяет
  зависшие;
- позволяет оператору пропустить профилирование.

Поддерживаемые runtime и сбор данных:

| Runtime | Диагностика |
|---|---|
| `java` | async-profiler `asprof` + `jcmd`/`jstack`; установка asprof с approval |
| `python` | `py-spy dump`; установка py-spy с approval |
| `go` | HTTP pprof на `localhost:6060` |
| `postgresql` | `pg_stat_activity`, `pg_stat_statements` |
| `mysql` | `SHOW FULL PROCESSLIST`, InnoDB status |
| `mongodb` | `mongosh db.currentOp()` |

Runtime сравнивается по точному lowercase-значению. Например, `postgres-16`
автоматически не распознаётся как `postgresql`.

## 9. Telegram-интерфейс

Бот создаётся только при наличии token и принимает сообщения/callback только от
`operatorChatId`. Остальные чаты игнорируются.

| Команда | Фактическое действие |
|---|---|
| `/hosts` | Кнопки хостов; детали инвентори, сервисов, telemetry и alertTypes |
| `/incidents` | Незакрытые инциденты; кнопки расследовать, пропустить profiling или закрыть активный |
| `/resolved` | Последние 20 закрытых инцидентов и количество recurrence |
| `/stop` | Останавливает текущее incident-расследование и возвращает его в OPEN |
| `/clear` | Удаляет только историю свободного диалога за текущую дату |
| `/help`, `/start` | Справка |
| любой другой текст | Свободный запрос к LLM с дневной историей |

`/ask` отдельной командой не реализован: он попадёт в общий LLM-диалог как
обычный текст.

Список хостов не показывает live health. Зелёный/красный статус сервиса в
деталях означает только отсутствие/наличие незакрытого инцидента.

Во время LLM-запроса бот редактирует progress message, показывает текущий tool,
число LLM-вызовов, tool calls и токены. Итог расследования и свободного запроса
также содержит статистику использования LLM. Длинные ответы разбиваются на
Telegram-сообщения до 4096 символов.

## 10. Конфигурация и деплой

Основные настройки:

| Настройка | Значение по умолчанию |
|---|---|
| `devops.kb.path` / `KB_PATH` | `./kb` |
| cheap check interval | 20 секунд |
| slow check interval | 60 секунд |
| exception scan interval | 120 секунд |
| bash timeout | 30 секунд |
| profiling duration | 60 секунд |
| profiling command timeout | 90 секунд |

Секреты и provider settings передаются через `.env`/`secrets.properties`:
`DEEPSEEK_API_KEY`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`,
`LLM_PROVIDER`, `TELEGRAM_TOKEN`, `TELEGRAM_CHAT_ID`, `TAVILY_API_KEY`.

Запуск:

```bash
# Локальная разработка
./gradlew bootRun

# Docker
DOCKER_BUILDKIT=1 docker compose up -d --build

# Пересборка на удалённом сервере; SERVER и APP_DIR берутся из bin/.env
./bin/deploy.sh
```

Docker Compose монтирует `kb`, `logs`, `repos` и `~/.ssh`. Entrypoint копирует
SSH-файлы в `/root/.ssh`, исправляет ownership/permissions и добавляет ключ
`altssh.bitbucket.org:443` в `known_hosts`.

## 11. Известные пробелы и ограничения текущей реализации

### Мониторинг и инциденты

- Реального мониторинга `systemdUnit`/container status нет. `SERVICE_DOWN` и
  `SERVICE_RECOVERED` не подключены к рабочему потоку.
- Health detector основан на переходах и не инициализируется успешным первым
  check. Поэтому первый длительный health failure после старта процесса может
  не создать `HEALTH_FAIL`.
- `alertTypes` фильтрует recovery-события тем же точным allowlist. Если разрешён
  `METRIC_HIGH`, но не `METRIC_RECOVERED`, авто-закрытие не произойдёт.
- Recovery во время `PROFILING` переводит инцидент в `OPEN` через skip, но
  edge-triggered recovery может больше не повториться, поэтому такой инцидент
  может остаться открытым.
- Нет persistent state для grace periods, cooldown, retry/backoff, maintenance
  windows, расписаний тишины, агрегации зависимых симптомов и приоритетной
  очереди. После рестарта состояния проверок теряются.
- `EXCEPTION_BURST` не считает частоту/количество исключений и не использует
  настраиваемый `logsCommand`.
- Если LLM не вызвал `resolveIncident`, OPEN-инцидент может снова автоматически
  попасть в расследование без backoff.

### Безопасность и управление

- Классификация shell-команд основана на префиксах, а не на гарантированно
  безопасной семантической политике или sandbox. «Всегда» превращает UNKNOWN
  prefix в read-only.
- Настроенные monitoring-команды и встроенный profiler выполняются вне
  `CommandRegistry`.
- Нет ролей, нескольких операторов, полноценного audit log approvals и
  отдельной политики разрешённых действий для сервиса. Старое YAML-поле
  `allowedActions` моделью игнорируется.
- `saveService` не является полноценным partial update и не позволяет через tool
  задать все поля модели.

### Масштабирование и эксплуатация

- Файловое хранилище сканируется целиком при поиске инцидентов; нет индексов,
  retention/archiving, блокировок для нескольких процессов и HA.
- Нет временных рядов, истории результатов checks, дашбордов, SLA/SLO,
  integrations с Prometheus/Grafana/Alertmanager.
- Одновременно расследуется только один инцидент. Свободный LLM-диалог может
  выполняться параллельно расследованию, при этом token tracker общий для
  процесса.
- `/stop` не останавливает свободный LLM-запрос и не останавливает profiling.
- Нет webhook/API/CLI, кроме Telegram и файлов инвентори.

### Покрытие тестами

Есть unit-тесты для `BashService`, `CommandRegistry`, `KnowledgeBaseService`,
`ExceptionScanner` и `ProfilingService`. Нет автоматических тестов ключевого
end-to-end поведения `MonitoringScheduler`, `IncidentManager`, Telegram flow,
LLM tools/provider integration и Docker/deploy.
