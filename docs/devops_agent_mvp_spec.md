# DevOps Agent MVP

## 1. Цель

Минимальная система, которая:
- мониторит Linux-хосты с сервисами (Java/Python/Go) и БД (PostgreSQL/MySQL/MongoDB)
- ходит на хосты по SSH — без агента на хосте
- при проблемах расследует и предлагает action
- запрашивает approval через Telegram
- после approval выполняет whitelisted действия по SSH
- хранит историю инцидентов и изменений в файлах на диске

---

## 2. Принципы

- **Central Agent** — единственный сервис, реализован на Java + Spring Boot + Spring AI
- **SSH** — единственный транспорт к хосту
- **Файлы на диске** — хранилище (JSON/YAML), без БД
- **Telegram Bot** — approval
- **LLM** работает только через typed tools, не через свободный shell

---

## 3. Компоненты

### Central Agent (Spring Boot)
- inventory хостов и сервисов
- scheduling polling loop
- SSH execution (read-only и whitelisted write)
- LLM-интеграция через Spring AI
- Telegram approval flow
- запись/чтение knowledge base из файлов

### Telegram Bot
- показать summary инцидента + proposed action
- кнопки: Approve / Deny / More diagnostics
- вернуть решение агенту
- принимать вопросы от оператора в свободной форме: "что с процессами на prod-1?", "проверь disk на billing-api", "почему упал сервис?" — агент отвечает через LLM + SSH

### Knowledge Base (файлы на диске)
```
kb/
  hosts/          # inventory хостов и сервисов
  snapshots/      # последние состояния хостов
  incidents/      # один файл = один инцидент
  changes/        # история выполненных команд
  patterns/       # known patterns + successful remediations
```

---

## 4. LLM Tools

LLM имеет ровно два инструмента.

### `ssh_exec`
- read-only команды — без approval
- write-actions — только после approval оператора
- аргументы строго типизированы, свободный shell запрещён
- используется для мониторинга, расследования, remediation и ответов на вопросы оператора

### `telegram_send`
- отправить сообщение оператору: summary инцидента, ответ на вопрос, запрос approval
- approval flow: `request_approval(incident_id, proposal)` + кнопки Approve/Deny/More diagnostics

KB читается и пишется напрямую агентом (не через LLM tool).

---

## 5. Что снимается по SSH

| Категория | Что |
|-----------|-----|
| Health | curl health URL или port check |
| Systemd | unit status, restart count, PID, uptime |
| Logs | journald tail, grep по паттерну |
| Host state | CPU, memory, disk, top processes |
| Config | cat config files |
| DB | ping / select 1 |
| Security | ssh auth failures, новые listeners |

---

## 6. Write-actions (whitelist)

Только эти, только после approval:
- `restart_service`
- `reload_service`
- `stop_service` / `start_service`
- `rotate_logs`
- `cleanup_tmp`
- `run_rollback_script`

---

## 7. Monitoring loop

**Каждые 15–30 сек:** health check, systemd active/failed, process alive

**Каждые 1–5 мин:** логи, CPU/memory/disk, security summary, DB ping

**По инциденту:** расширенные логи, конфиги, Git lookup, web search

### Базовый цикл
1. Взять host из inventory
2. Выполнить cheap checks по SSH
3. Сравнить с предыдущим snapshot
4. Нет проблем → сохранить snapshot
5. Есть аномалия → создать/обновить инцидент → запустить investigation

---

## 8. Investigation loop

1. Прочитать host/service profile из KB
2. Найти похожие инциденты
3. Снять дополнительные данные по SSH
4. Если stacktrace — код из Git
5. Сформировать гипотезу + confidence
6. Решить: зафиксировать / more diagnostics / запросить approval на action

---

## 9. Approval (Telegram)

Сообщение содержит: host, service, severity, что сломалось, гипотезу, proposed action, риск.

Кнопки: **Approve** / **Deny** / **More diagnostics**

Approval привязан к инциденту, логируется кто и когда апрувнул.

---

## 10. Модель данных (файлы)

### hosts/{id}.yaml
`id, name, env, ip, ssh_target, notes`

### hosts/{id}/services/{service_id}.yaml
`id, name, runtime, systemd_unit, health_url, repo_url, config_files, allowed_actions`

### snapshots/{host_id}/latest.json
`ts, cpu, memory, disk, top_processes, service_statuses`

### incidents/{id}.json
`id, host_id, service_id, status, severity, started_at, resolved_at, summary, root_cause_hypothesis, confidence, events[]`

### changes/{id}.json
`id, host_id, service_id, ts, command, args, result, approval_id, approved_by`

### patterns/{id}.json
`id, host_id?, service_id?, pattern_type, summary, remediation_hint, success_rate`

---

## 11. Inventory entry

```yaml
service: billing-api
host: prod-1
ssh_target: ubuntu@prod-1
runtime: java
systemd_unit: billing-api.service
health_url: http://127.0.0.1:8080/actuator/health
repo_url: git@github.com:org/billing.git
config_files:
  - /opt/billing/config/application-prod.yml
allowed_actions:
  - restart_service
  - reload_service
```

---

## 12. Стек

- **Java 21 + Spring Boot 3**
- **Spring AI** — LLM integration + tool calling
- **Spring Scheduler** — polling loop
- **Jsch / Apache MINA SSHD** — SSH client
- **TelegramBots** — Telegram bot

---

## 13. Порядок разработки

**Этап 1**
- inventory (YAML файлы)
- SSH client + read-only templates
- polling loop + snapshots
- incident creation
- Telegram approval
- 2-3 write-actions
- KB запись/чтение

**Этап 2**
- LLM investigation через Spring AI tools
- Git integration + code-aware RCA
- similar incident retrieval из KB
- patterns + successful remediations

**Этап 3**
- performance profiling (JFR, pprof)
- weekly optimization proposals

---

## 14. Критерии готовности MVP

1. Регулярно ходит по SSH на хост
2. Обнаруживает падение сервиса, burst исключений, disk/memory/CPU pressure
3. Создаёт инцидент и собирает факты
4. Показывает summary в Telegram
5. После approval перезапускает сервис
6. Записывает инцидент и изменение в KB
7. На следующем похожем инциденте подтягивает историю и успешные remediation