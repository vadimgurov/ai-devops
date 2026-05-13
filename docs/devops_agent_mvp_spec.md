# DevOps Agent — спецификация

## 1. Цель

Автономный агент, который:
- мониторит Linux-хосты с сервисами (Java/Python/Docker) и БД (MySQL/PostgreSQL)
- ходит на хосты по SSH — без агента на хосте
- при аномалиях расследует причину через LLM и предлагает действие
- запрашивает подтверждение write-действий через Telegram
- после подтверждения выполняет команду по SSH
- хранит историю инцидентов и переписку с LLM в файлах на диске

---

## 2. Принципы

- **Central Agent** — единственный сервис, Java 21 + Spring Boot 3.4 + Spring AI
- **SSH** — единственный транспорт к хосту (ProcessBuilder + `ssh`)
- **Файлы на диске** — хранилище (JSON/YAML), без БД
- **Telegram Bot** — интерфейс оператора и approval flow
- **LLM** работает через typed tools (Spring AI tool calling)

---

## 3. Компоненты

### Мониторинг
- **MonitoringScheduler** — два цикла: дешёвые проверки (по умолчанию 20 сек) и медленные (60 сек)
- **AnomalyDetector** — сравнивает значения телеметрии с порогами, отслеживает восстановление; поддерживает `minDurationMs` — алерт только если аномалия длится дольше порога
- **ExceptionScanner** — ищет исключения в логах сервисов (journald/docker); дедуплицирует по fingerprint (первая строка, 120 символов); повторные исключения того же класса тихо добавляются к уже закрытому инциденту без перерасследования

### LLM-агент (`LlmAgent`)
ChatClient с системным промптом и набором инструментов. Одно расследование одновременно (single-thread pool). История диалога хранится в KB и подрезается до последних 20 сообщений.

**Инструменты:**

| Инструмент | Назначение |
|---|---|
| `bash(hostId, command)` | SSH на хост или локальная команда; классифицирует команду перед выполнением |
| `getInventory()` | Список хостов, сервисов, телеметрии |
| `saveHost / saveService` | Upsert хоста/сервиса (сохраняет telemetry/alertTypes при обновлении базовых полей) |
| `saveTelemetryCheck` | Upsert телеметрии по name+command (дедупликация) |
| `setHostAlertTypes` | Фильтр типов аномалий для хоста |
| `searchSimilarIncidents` | Похожие закрытые инциденты по ключевым словам |
| `getIncident` | Полная история инцидента (события, гипотеза) |
| `updateIncidentHypothesis` | Сохранить гипотезу о причине |
| `resolveIncident` | Закрыть инцидент с итоговым выводом |
| `updateSourceCode(serviceId)` | git clone/pull репозитория сервиса локально; возвращает путь |
| `search(query)` | Поиск в интернете через Tavily API (опционально) |

**Классификация команд (`CommandRegistry`):**
- `READ_ONLY` — выполняются без вопросов (journalctl, cat, ls, ps, df, find, echo и др. — часть захардкожена, часть из YAML)
- `WRITE_ACTION` — требуют подтверждения оператора
- `UNKNOWN` — требуют подтверждения, оператор может нажать «разрешить всегда» (сохраняется в YAML)
- `git clone/pull` через bash всегда отклоняется — нужно использовать `updateSourceCode`

### Telegram Bot
Команды оператора:

| Команда | Действие |
|---|---|
| `/hosts` | Список хостов со статусами сервисов, телеметрией и alertTypes |
| `/incidents` | Открытые инциденты с кнопками управления |
| `/resolved` | Последние 20 закрытых инцидентов с количеством повторений |
| `/ask <текст>` | Свободный вопрос агенту (с историей диалога) |
| `/stop` | Остановить текущее расследование |
| `/clear` | Очистить историю диалога |

Approval flow: агент присылает описание команды с кнопками **Разрешить / Разрешить всегда / Отклонить**. Таймаут ожидания — 5 минут.

### Knowledge Base (файлы на диске)

```
kb/
  allowed_commands.yaml          # whitelist команд (read-only / write-action)
  hosts/
    <id>.yaml                    # конфиг хоста: ip, sshTarget, telemetry, alertTypes
    <id>/services/<svc>.yaml     # конфиг сервиса: runtime, systemdUnit, logsCommand, repoUrl, ...
  incidents/
    <id>/incident.json           # инцидент: статус, гипотеза, события
    <id>/conversation.json       # история диалога LLM для инцидента
  conversations/
    session-<date>.json          # история свободных диалогов /ask (по дате)
```

---

## 4. Модель данных

### Host (`hosts/<id>.yaml`)
`id, name, env, ip, sshTarget, notes, telemetry[], alertTypes[]`

### ServiceConfig (`hosts/<id>/services/<svc>.yaml`)
`id, name, hostId, runtime, systemdUnit, containerName, healthCheck, versionUrl, sourcesPath, repoUrl, logsCommand, configFiles[], allowedActions[]`

### TelemetryCheck
`name, command, threshold, minDurationMs`

### Incident (`incidents/<id>/incident.json`)
`id, hostId, serviceId, status, severity, startedAt, resolvedAt, summary, rootCauseHypothesis, confidence, events[]`

**Статусы:** `OPEN → INVESTIGATING → RESOLVED` (или `OPEN → PROFILING → OPEN → INVESTIGATING → RESOLVED`)

**Типы событий:** `recurrence, hypothesis_updated, resolved, duplicate_closed, metric_recovered, profiling_complete`

---

## 5. Типы аномалий

| Тип | Что детектит | Поведение |
|---|---|---|
| `HEALTH_FAIL` | healthCheck вернул ненулевой exit | Создаёт инцидент HIGH, запускает расследование |
| `METRIC_HIGH` | телеметрия превысила порог дольше minDurationMs | Создаёт инцидент MEDIUM; для CPU запускает профайлинг |
| `EXCEPTION_BURST` | N исключений в логах за интервал | Создаёт инцидент HIGH; повтор того же класса исключений — тихо добавляет событие к закрытому инциденту |
| `SERVICE_DOWN` | healthCheck недоступен | Создаёт инцидент HIGH |
| `*_RECOVERED` | метрика/сервис восстановились | Закрывает инцидент если расследование ещё не началось |

Фильтр `alertTypes` на хосте ограничивает, на какие типы агент реагирует.

---

## 6. Профайлинг (CPU)

При `METRIC_HIGH` для CPU-метрики инцидент переводится в статус `PROFILING` и запускается `ProfilingService`:
- определяет runtime процесса (`/proc/<pid>/cmdline`)
- Java → `asprof` (async-profiler), Python → `py-spy`
- результат сохраняется в событие `profiling_complete` и передаётся LLM при расследовании

---

## 7. Стек

- **Java 21 + Spring Boot 3.4** (non-web приложение)
- **Spring AI** — ChatClient, tool calling, CompactLoggingAdvisor
- **Spring Scheduler** — `@Scheduled` для циклов мониторинга
- **ProcessBuilder** — SSH и локальные команды (не Jsch)
- **Jackson** — JSON/YAML сериализация
- **TelegramBots** — Telegram Bot API
- **Docker + BuildKit** — сборка и деплой; Gradle-кеш через `--mount=type=cache`

---

## 8. Деплой

```bash
# Пересборка и запуск на удалённом сервере
./bin/deploy.sh          # читает SERVER и APP_DIR из bin/.env

# Прямой запуск
DOCKER_BUILDKIT=1 docker compose up -d --build
```

SSH-ключи монтируются из `~/.ssh` хоста. Entrypoint-скрипт копирует их в `/root/.ssh` с правильными правами (OpenSSH игнорирует `$HOME`, читает UID из `/etc/passwd`). Ключ Bitbucket добавляется в `known_hosts` при каждом старте.