# Project Rules

## Testing

- Cover new features with unit tests.
- Run `./gradlew test` after every change before reporting the task as done.

## Architecture: BashService

`BashService` is a thin execution layer between LLM tools and `BashRunner`.

**Responsibilities:**
- `exec(command)` — run an already-built shell command (may include SSH prefix)
- `execLocal(command)` — run a command locally on the agent machine

**Not responsible for:**
- Building SSH command strings — that is `BashTool`'s job
- Classifying commands (read-only vs write) — `CommandRegistry` + `BashTool`
- Approval flow — `ApprovalService` + `BashTool`
- Audit logging of write actions — `BashTool` or caller

## Architecture: InventoryLoader

`InventoryLoader` — in-memory реестр хостов и сервисов, загруженных из `kb/hosts/`.

**Responsibilities:**
- Загрузить все хосты (`hosts/*.yaml`) и их сервисы (`hosts/<id>/services/*.yaml`) при старте (`@PostConstruct`)
- Держать актуальную in-memory копию (`Map<id, Host>`) — единственный источник истины на время работы
- Сохранять изменения хоста / сервиса / телеметрии на диск и синхронно обновлять in-memory копию

**Not responsible for:**
- Инциденты, снимки, историю команд — это `KnowledgeBaseService`
- Мониторинг и проверки хостов — это `MonitoringScheduler`

## Prefer annotations over manual infrastructure

If Spring (or any framework in use) provides an annotation that does the job, use it — never write manual infrastructure code instead.

- **Scheduling:** use `@Scheduled(fixedDelay = ..., initialDelay = ...)` instead of `ScheduledExecutorService` + `scheduleWithFixedDelay`
- **Lifecycle:** use `@PostConstruct` / `@PreDestroy` instead of manual init/destroy hooks

`ExecutorService` for dynamically submitted tasks (worker pools, one-off jobs) is still fine — that's not a scheduling concern.

## Anti-patterns to avoid

### Feature Envy / Law of Demeter violation

**Do not** pass a rich object to a method that only uses one field from it:

```java
// BAD — method only needs host.id() but forces dependency on Host
void exec(Host host, String command) {
    log.info("running on {}", host.id()); // only this
    runner.run(command);
}

// GOOD — accept only what you actually need
void exec(String command) {
    runner.run(command);
}
```

**Why:** unnecessary coupling to `Host` class. If `Host` changes, `exec` must change too — for no reason. Methods should declare exactly what they need, nothing more (Law of Demeter). This smell is called **Feature Envy**: the method is more interested in another class's data than in doing its own job.
