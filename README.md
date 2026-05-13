# AI DevOps Agent

Автономный агент для мониторинга Linux-серверов и расследования инцидентов с использованием LLM (DeepSeek).

## Что это и какую проблему решает

Агент следит за хостами, обнаруживает аномалии и самостоятельно разбирается в причинах — читает логи, проверяет процессы, изучает исходный код сервисов, ищет похожие инциденты в истории и предлагает конкретные действия. Оператор получает результат в Telegram и решает, что делать дальше.

**Подходит если:**
- Один человек обслуживает несколько серверов и не хочет дежурить ночью
- Инциденты повторяются и нужна история расследований
- Хочется понять корневую причину, а не просто получить алерт

**Ограничения:**
- Агент не принимает решения автоматически: restart, rollback и другие write-действия требуют явного подтверждения оператора в Telegram
- Не заменяет профессиональный мониторинг (Prometheus, Grafana) — скорее дополняет его
- Качество расследования зависит от качества LLM и доступности исходников сервиса
- Один агент, одно расследование одновременно — не рассчитан на десятки хостов

---

## Техническая структура

```
Spring Boot (non-web) + Spring AI
│
├── Мониторинг
│   ├── MonitoringScheduler   — периодически запускает телеметрию и healthcheck-и
│   ├── AnomalyDetector       — сравнивает значения с порогами, детектирует восстановление
│   ├── ExceptionScanner      — ищет исключения в логах systemd/docker сервисов
│   └── IncidentManager       — открывает/закрывает инциденты, запускает расследование
│
├── LLM-агент
│   ├── LlmAgent              — ChatClient со списком инструментов и системным промптом
│   ├── BashTool              — выполняет команды локально или по SSH с классификацией
│   ├── InventoryTool         — читает и обновляет инвентори (хосты, сервисы, телеметрия)
│   ├── IncidentTool          — работа с инцидентами из LLM (гипотезы, закрытие)
│   ├── SourceCodeTool        — git clone/pull репозитория сервиса для анализа кода
│   └── WebSearchTool         — поиск через Tavily API (ошибки, баги, документация)
│
├── База знаний (kb/)
│   ├── hosts/<id>.yaml       — конфиг хоста: SSH, телеметрия, alertTypes
│   ├── hosts/<id>/services/  — конфиги сервисов: runtime, healthcheck, repoUrl, logsCommand
│   ├── incidents/<id>/       — история инцидентов и переписка с LLM
│   └── allowed_commands.yaml — whitelist команд (read-only / write-action)
│
└── Telegram-бот
    ├── DevopsTelegramBot     — команды (/hosts, /incidents, /resolved, /ask, ...)
    └── ApprovalService       — запрос подтверждения на write-действия
```

**Стек:** Java 21, Spring Boot 3.4, Spring AI, DeepSeek API, Telegram Bot API, Jackson/YAML, Docker.

**Классификация команд** (`CommandRegistry`): команды делятся на `READ_ONLY` (выполняются без вопросов), `WRITE_ACTION` (требуют подтверждения) и `UNKNOWN` (тоже требуют подтверждения, с возможностью «разрешить всегда»).

---

## Как запустить

### 1. Переменные окружения

Создай файл `.env` рядом с `docker-compose.yml`:

```env
DEEPSEEK_API_KEY=sk-...
TELEGRAM_TOKEN=123456:ABC...
TELEGRAM_CHAT_ID=<твой chat id>
TAVILY_API_KEY=tvly-...          # опционально, для поиска в интернете
KB_PATH=/app/kb
```

### 2. Инвентори

Создай `kb/hosts/<имя-хоста>.yaml`:

```yaml
id: "my-server"
name: "My Server"
env: "production"
ip: "1.2.3.4"
sshTarget: "ubuntu@1.2.3.4"
notes: "Описание хоста"
telemetry:
  - name: "cpu"
    command: "vmstat 1 2 | awk 'END{print 100-$15}'"
    threshold: 85.0
    minDurationMs: 300000       # алерт только если > порога 5 минут подряд
  - name: "ram"
    command: "free | awk '/Mem:/ {printf \"%.0f\", $3/$2*100}'"
    threshold: 85.0
```

Создай `kb/hosts/<имя-хоста>/services/<сервис>.yaml`:

```yaml
id: "my-app"
name: "My App"
hostId: "my-server"
runtime: "Java 17 (Spring Boot)"
systemdUnit: "my-app.service"
logsCommand: "journalctl -u my-app.service --no-pager -n 200"
repoUrl: "git@github.com:org/my-app.git"
healthCheck: "curl -fsS http://localhost:8080/actuator/health > /dev/null"
allowedActions: ["restart", "logs", "status"]
```

Агент сам может создавать и обновлять записи через инструменты `saveHost` / `saveService`.

### 3. SSH-ключи

Положи ключ для доступа к серверам в `~/.ssh/`. Агент монтирует эту директорию в контейнер и копирует с правильными правами при старте. Если Bitbucket/GitHub доступен только через порт 443, добавь в `~/.ssh/config`:

```
Host bitbucket.org
    HostName altssh.bitbucket.org
    Port 443
```

### 4. Запуск

```bash
# Локально (для разработки)
./gradlew bootRun

# В Docker (продакшн)
DOCKER_BUILDKIT=1 docker compose up -d --build
```

### 5. Деплой на удалённый сервер

Отредактируй `bin/.env`:

```env
SERVER=user@host
APP_DIR=~/ai-devops
```

Затем:

```bash
./bin/deploy.sh
```

Скрипт выполняет `git pull` на сервере и пересобирает контейнер. Gradle-зависимости кешируются между сборками через BuildKit mount cache.

### Команды бота

| Команда | Действие |
|---|---|
| `/hosts` | Список хостов и статус сервисов |
| `/incidents` | Открытые инциденты |
| `/resolved` | Закрытые инциденты (последние 20) |
| `/ask <вопрос>` | Вопрос агенту в свободной форме |
| `/stop` | Остановить текущее расследование |
| `/clear` | Очистить историю диалога |