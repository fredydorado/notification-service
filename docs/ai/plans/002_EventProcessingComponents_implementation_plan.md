# Feature 002 — Event Processing Components: Implementation Plan & Handover

> **Status: complete.** The 002 feature is implemented and the **full `mvn test` suite
> passes (80 tests, 0 failures)** - including every integration test - on the original
> Windows machine. The earlier assumption that the work had to move to a Linux box was
> wrong: the blockers were two environment faults and two code defects, all of which are
> now fixed and documented in section 6.
>
> This file is kept as the record of what was built and what was actually wrong.
> For day-to-day build/run/test instructions see `README.md`; for agent-facing
> conventions see `AGENTS.md` and `docs/ai/rules/*.md`.

---

## 1. Project snapshot

- Repo: `notification-service` — single-module Spring Boot **4.1.1** / **Java 21** / Maven.
- Package root: `com.fardorado.notification`.
- Hexagonal architecture (ports & adapters), enforced conventions in:
  - `AGENTS.md`
  - `docs/ai/rules/ARCHITECTURE_CONVENTIONS.md`
  - `docs/ai/rules/NAMING_CONVENTIONS.md`
  - `docs/ai/rules/PROJECT_GUIDELINES.md`
- Feature specs:
  - `docs/spec/features/001_createPersistenceComponents.md` (persistence; **updated** to match the current schema)
  - `docs/spec/features/002_EventProcessingComponents.md` (this feature — the authoritative requirements; includes a "Definition of Done" checklist at the end)
- Sample broker events used verbatim as test data: `docs/spec/sample/notification_events.json`
  (copied to `src/test/resources/sample/notification_events.json`).
- Commands: no Maven wrapper — use system `mvn`. `mvn spring-boot:run`, `mvn test`,
  `mvn test -Dtest=SomeTest`, `mvn package`.
- **`mvn test` requires a working Docker daemon** (Testcontainers PostgreSQL `postgres:latest`
  + Kafka `apache/kafka:latest`, declared in `src/test/java/com/fardorado/notification/TestcontainersConfiguration.java`).

**All changes for feature 002 are committed.** `mvn test` additionally needs `DOCKER_HOST`
where Docker lives inside WSL2 rather than Docker Desktop - see section 6.3 and `README.md`.

---

## 2. What was implemented

### Phase 0 — Schema corrections (edited the v1.0 Liquibase changesets in place — they had never been executed against any DB)

`src/main/resources/db/changelog/v1.0/`:

- **`001-create-subscription.yaml`**: `subscriber_id`→`client_id`; `channel` column removed;
  `endpoint`→`webhook_url` (type `text`); unique → `uq_subscription_client_event_type (client_id, event_type)`;
  index → `idx_subscription_event_type_status (event_type, status)`.
- **`002-create-notification-event.yaml`**: added `event_id` (varchar(100) NOT NULL,
  `uq_notification_event_event_id` — durable idempotency), `event_version` (int NOT NULL default 1),
  `correlation_id` (varchar(100) nullable), `subscription_id` (bigint nullable,
  `fk_notification_event_subscription` → `subscription.id`), `next_attempt_at` (timestamptz nullable).
- **`003-create-delivery-attempt.yaml`**: `channel` column removed (only channel is WEBHOOK, implicit).
- `docs/spec/features/001_createPersistenceComponents.md` updated to reflect all of the above.

### Phase 1 — Domain + persistence adjustments

- `EventType` extended with the 10 sample event types (`DEBIT_CARD_WITHDRAWAL`, `CREDIT_REFUND`,
  `DEBIT_TRANSFER`, `CREDIT_DEPOSIT`, `DEBIT_PURCHASE`, `CREDIT_CASHBACK`, `DEBIT_SUBSCRIPTION`,
  `DEBIT_AUTOMATIC_PAYMENT`, …) alongside the original 3.
- `NotificationChannel` enum **deleted**; `channel` removed from `Subscription`, `DeliveryAttempt`,
  all entities/mappers/repositories/ports.
- `Subscription`: `clientId`, `webhookUrl`.
- `NotificationEvent` (domain): `eventId`, `eventVersion`, `correlationId`, `subscriptionId`,
  `nextAttemptAt` + transitions `markDelivering()` (clears `nextAttemptAt`), `scheduleRetry(Instant)`,
  `markCompleted()`, `markFailed()`, `markUnmatched()` (PENDING→FAILED, no active subscription),
  `replay()` (FAILED→PENDING). The state machine stays centralized in the domain entity.
- `NotificationEventJpaRepository.findClaimableEvents(...)`: `FOR UPDATE SKIP LOCKED`
  (`jakarta.persistence.lock.timeout=-2`) claim of `PENDING ∪ (RETRY_SCHEDULED ∧ next_attempt_at ≤ now)
  ∪ (DELIVERING ∧ updated_at ≤ now − lease)`, oldest first, limited.
- Ports: `NotificationEventRepository.{save, findById, findByEventId, claimDeliverable(limit, now, staleThreshold)}`;
  `SubscriptionRepository.findByClientIdAndEventTypeAndStatus(...)`; `DeliveryAttemptRepository.{findById,
  findByNotificationEventIdOrderByAttemptNumber, nextAttemptNumber, findByNotificationEventIdAndStatus}`.

### Phase 2 — Application layer (`application.*`, `configuration.*`)

- `application/command/`: `ProcessNotificationEventCommand`, `ProcessDeliveryCommand`.
- `application/port/in/`: `ProcessNotificationEventUseCase`, `ProcessDeliveryUseCase`, `DispatchDeliveriesUseCase`.
- `application/port/out/`: `NotificationChannelClient` (webhook abstraction),
  `WebhookDeliveryCommand`, `WebhookDeliveryResult` (`SUCCESS` / `RETRYABLE_FAILURE` / `PERMANENT_FAILURE`).
- `application/service/`:
  - `ProcessNotificationEventUseCaseImpl` — `@Transactional`; idempotent via `findByEventId`
    (duplicate → log + skip); matches subscription via `SubscriptionMatcher` (`client_id + event_type + ACTIVE`);
    no match → persist as FAILED via `markUnmatched()`. Broker ack happens only after this returns.
  - `SubscriptionMatcher`, `RetryPolicy` (exhaustion + exponential backoff, config-backed, pure/testable).
  - `DeliveryClaimService` — one short `@Transactional`: claims (SKIP LOCKED), PENDING/due-RETRY →
    `DELIVERING` + new `IN_PROGRESS` attempt (append-only) + returns `ProcessDeliveryCommand`;
    stale `DELIVERING` → fail the stale `IN_PROGRESS` attempt + retry-policy → `RETRY_SCHEDULED` or `FAILED`
    (never silently completed, never redelivered in the same claim).
  - `DeliveryResultService` — one short `@Transactional` after the webhook call: attempt
    SUCCESS/FAILED + event `COMPLETED` / `scheduleRetry(policy.nextAttemptAt)` / `FAILED`;
    catches `OptimisticLockingFailureException` + `InvalidStatusTransitionException` → log, concurrent state authoritative.
  - `ProcessDeliveryUseCaseImpl` — webhook call **outside any tx** (MDC: eventId/correlationId/attemptNumber);
    unexpected worker exceptions recorded as retryable so nothing is left stuck.
  - `DispatchDeliveriesUseCaseImpl` — claims a batch, submits each command to the bounded worker executor.
- `configuration/NotificationProcessingProperties` (`@ConfigurationProperties("notification.processing")`:
  maxAttempts=5, backoffInitial=30s, backoffMax=10m, leaseTimeout=5m, claimBatchSize=50, workerPoolSize=10).

### Phase 3 — Adapters + config

- `adapter/in/messaging/KafkaNotificationConsumer` — `@KafkaListener` topic from
  `notification.kafka.topic` (default `notification-events`); envelope record
  `NotificationEventKafkaMessage` (snake_case, ignores unknown fields like `delivery_date`/`delivery_status`);
  validates (blank fields, unknown event_type); plain-text `content` wrapped as `{"content": "..."}`
  (payload column is JSONB), structured JSON kept verbatim; **manual ack only after durable persistence**;
  poison messages logged + acked (explicit decision so one bad event cannot block the partition);
  persistence failures propagate → no ack → container `DefaultErrorHandler` redelivers.
- `adapter/out/webhook/WebhookChannelClientImpl` — `RestClient` (connect 5s / read 10s);
  POST envelope `{event_id, event_type, event_version, correlation_id, content}` + `X-Correlation-Id`
  header; classification: 2xx → SUCCESS, 429/5xx → RETRYABLE, other 4xx → PERMANENT,
  IO/timeout → RETRYABLE. Never changes event/attempt state itself.
- `adapter/in/scheduler/NotificationEventDispatchScheduler` — `@Scheduled(fixedDelayString =
  "${notification.processing.poll-interval:5s}")`, disabled via `notification.processing.dispatch-enabled=false`
  (`@ConditionalOnProperty`, default enabled).
- `configuration/NotificationProcessingConfiguration` — `@EnableScheduling`, `Clock` bean (UTC),
  **`deliveryWorkerExecutor`**: `SimpleAsyncTaskExecutor` with `setVirtualThreads(true)` +
  `setConcurrencyLimit(workerPoolSize)` (bounded virtual-thread worker pool; submissions block
  when saturated — claimed events are never lost).
- `application.yaml` — Kafka consumer (group `notification-service`, earliest, auto-commit off,
  String/String deserializers, listener `ack-mode: manual_immediate`) + `notification.kafka.topic`
  + all `notification.processing.*` values.

### Phase 4 — Tests

**Unit tests — all pass (50 tests, `BUILD SUCCESS` verified):**
domain (`NotificationEventTest`, `DeliveryAttemptTest`, `SubscriptionTest`), `RetryPolicyTest`,
application with in-memory fakes (`ProcessNotificationEventUseCaseImplTest`, `DeliveryClaimServiceTest`,
`ProcessDeliveryUseCaseImplTest`, fakes `Fake*Repository`), adapter tests
(`KafkaNotificationConsumerTest` — envelope mapping/validation/poison; `WebhookChannelClientImplTest` —
real HTTP against a local JDK `HttpServer` stub, classification incl. 429/404/500/connection failure).

**Integration tests — all passing against real PostgreSQL and Kafka via Testcontainers
(two of them needed fixes first, see section 6):**
- `SubscriptionPersistenceIntTest`, `NotificationEventPersistenceIntTest`, `DeliveryAttemptPersistenceIntTest`
  (updated to the new model; cover claim semantics incl. SKIP LOCKED double-claim and stale-claim).
- `KafkaNotificationConsumerIntTest` — publishes the **verbatim sample events** to a real Kafka topic,
  asserts durable ingestion (one row per `event_id`, metadata preserved, linked subscription),
  duplicate redelivery → no extra rows, poison messages don't block the partition.
  Runs with `dispatch-enabled=false` so assertions target PENDING rows.
- `EventProcessingFlowIntTest` — end-to-end with a local JDK `HttpServer` webhook stub
  (`/success`, `/flaky` = 500 then 200, `/always-fail`, `/not-found`): success → COMPLETED;
  transient failure → new append-only attempt then COMPLETED; exhausted retries (max-attempts=2) → FAILED;
  404 → permanent FAILED (1 attempt); stale DELIVERING recovery (backdated `updated_at` via JDBC)
  → stale attempt FAILED + retry appended → COMPLETED. Fast test props: poll-interval 200ms,
  backoff 100ms/200ms cap, lease 2s.

---

## 3. Final test results

Run on Windows with Docker reachable inside WSL2 (see section 6 and `README.md`):

```
DOCKER_HOST=tcp://<wsl-ip>:2375 mvn test
```

```
Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Test class | Tests | Covers |
|---|---|---|
| `NotificationEventTest`, `DeliveryAttemptTest`, `SubscriptionTest` | 20 | state machine, invalid transitions |
| `RetryPolicyTest` | 4 | retryability, exhaustion, backoff |
| `ProcessNotificationEventUseCaseImplTest`, `DeliveryClaimServiceTest`, `ProcessDeliveryUseCaseImplTest` | 17 | idempotency, claiming, delivery orchestration |
| `KafkaNotificationConsumerTest`, `WebhookChannelClientImplTest` | 9 | envelope mapping, poison messages, HTTP classification |
| `SubscriptionPersistenceIntTest`, `NotificationEventPersistenceIntTest`, `DeliveryAttemptPersistenceIntTest` | 21 | Liquibase + `ddl-auto=validate`, `SKIP LOCKED` claiming, optimistic locking |
| `KafkaNotificationConsumerIntTest` | 3 | verbatim sample events, duplicate redelivery, poison messages |
| `EventProcessingFlowIntTest` | 5 | success, transient retry, exhausted retries, permanent 4xx, stale `DELIVERING` recovery |
| `NotificationServiceApplicationTests` | 1 | context loads with defaults |

`mvn package` builds the jar with the same suite green.

Everything in the 002 spec's Definition of Done (section 29) is implemented and
exercised by the tests above: `event_id` identity, ack-after-persistence, durable
idempotency, the state machine, append-only delivery attempts, no `deliveries` table,
webhook behind `NotificationChannelClient`, an isolated `RetryPolicy`, database-backed
retries, `FOR UPDATE SKIP LOCKED` claiming, a bounded worker pool, stale `DELIVERING`
recovery, optimistic locking, and no transaction around the HTTP call.

---

## 4. Design decisions taken (context for review conversations)

1. `webhook_url` (TEXT) **is** the webhook URL used directly by the HTTP client (per the reviewer's correction).
2. Matching = `(client_id, event_type)` with status ACTIVE; DB unique constraint guarantees at most one;
   **no match → event persisted directly as FAILED** (`markUnmatched`, PENDING→FAILED) — observable, no delivery attempts.
3. Poison messages (malformed/invalid) are **logged + acked** so they can't block the partition;
   persistence failures are NOT acked (redelivery via the container's error handler).
4. `EventType` currently holds the original 3 + the 10 sample types; near-duplicates
   (`CASH_WITHDRAWAL` vs `DEBIT_CARD_WITHDRAWAL`) are left in deliberately for the reviewer to prune.
5. `FAILED → PENDING` replay exists in the domain but no driving operation was built (spec says
   it's only for an explicit replay feature).
6. A recovered stale DELIVERING event is **not** redelivered in the same claim — it goes through
   `RETRY_SCHEDULED` + backoff like any retry.
7. Worker pool = virtual threads with an explicit concurrency limit (bounded per spec §14);
   when saturated, dispatcher submission blocks (no loss; the claim tx has already committed).
8. The dispatcher sweeps PENDING + due RETRY_SCHEDULED + stale DELIVERING uniformly
   (crash-safe: no stranded PENDING events after a restart).

---

## 5. Key file map (new files only)

```
src/main/java/com/fardorado/notification/
  application/command/ProcessNotificationEventCommand.java
  application/command/ProcessDeliveryCommand.java
  application/port/in/ProcessNotificationEventUseCase.java
  application/port/in/ProcessDeliveryUseCase.java
  application/port/in/DispatchDeliveriesUseCase.java
  application/port/out/NotificationChannelClient.java
  application/port/out/WebhookDeliveryCommand.java
  application/port/out/WebhookDeliveryResult.java
  application/service/ProcessNotificationEventUseCaseImpl.java
  application/service/ProcessDeliveryUseCaseImpl.java
  application/service/DispatchDeliveriesUseCaseImpl.java
  application/service/DeliveryClaimService.java
  application/service/DeliveryResultService.java
  application/service/SubscriptionMatcher.java
  application/service/RetryPolicy.java
  adapter/in/messaging/KafkaNotificationConsumer.java
  adapter/in/messaging/NotificationEventKafkaMessage.java
  adapter/in/scheduler/NotificationEventDispatchScheduler.java
  adapter/out/webhook/WebhookChannelClientImpl.java
  configuration/NotificationProcessingConfiguration.java
  configuration/NotificationProcessingProperties.java

src/test/java/com/fardorado/notification/
  KafkaNotificationConsumerIntTest.java
  EventProcessingFlowIntTest.java
  adapter/in/messaging/KafkaNotificationConsumerTest.java
  adapter/out/webhook/WebhookChannelClientImplTest.java
  application/service/*  (use case tests + Fake*Repository fakes)
  domain/model/**/*Test.java

src/test/resources/sample/notification_events.json   (verbatim copy of the broker sample)
```

Modified (not new): the three Liquibase changesets, `application.yaml`, all persistence
entities/repositories/ports, the domain model, the three pre-existing persistence IntTests, and
`docs/spec/features/001_createPersistenceComponents.md`.

---

## 6. What was actually broken (and the fixes)

The suite failed for four independent reasons - two environment faults and two code
defects. None of them required moving to Linux.

### 6.1 Jackson 2 vs Jackson 3 (code defect)

Every integration test failed the Spring context with:

```
No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'
```

Spring Boot 4.1 ships **Jackson 3** (`tools.jackson`, 3.1.5) and auto-configures a
`JsonMapper` bean. Jackson 2 is on the classpath only transitively, so
`KafkaNotificationConsumer` and `WebhookChannelClientImpl` compiled against
`com.fasterxml.jackson.databind.ObjectMapper` but nothing could inject it.

**Fix:** migrated both classes plus `NotificationEventKafkaMessage` and the four
affected tests to `tools.jackson`, injecting `tools.jackson.databind.json.JsonMapper`.
Annotations stay in `com.fasterxml.jackson.annotation`, which Jackson 3 still uses.

### 6.2 `Selector.open()` fails machine-wide (environment)

`java.nio.channels.Selector.open()` failed in **every** JVM on the machine with
`IOException: Unable to establish loopback connection` /
`SocketException: Invalid argument: connect`. The JDK backs the selector's wakeup
pipe with an AF_UNIX socket created in `%TEMP%`; in that folder the socket binds but
cannot be connected to, nor even deleted ("The file cannot be accessed by the
system"). Almost certainly endpoint-security filter-driver interference. It is
**not** the 8.3 short name - the long form `C:\Users\<user>\AppData\Local\Temp`
fails identically, while `C:\Temp`, `C:\Windows\Temp` and a project `target/` all work.

This breaks anything selector-based: the JDK `HttpServer` webhook stubs, Kafka
clients, Netty, Testcontainers.

**Fix:** a Windows-only profile in `pom.xml` sets
`-Djdk.net.unixdomain.tmpdir=${project.build.directory}` for surefire.
`-Djava.io.tmpdir` is **not** an equivalent - `UnixDomainSockets` reads the `TEMP`
environment variable, not that property. Fixing `TMP`/`TEMP` at the OS level would
fix it machine-wide.

### 6.3 Docker unreachable from the Windows JVM (environment)

Docker 25.0.5 does run on this machine, as a native daemon inside the WSL2 `Ubuntu`
distro, with an `alpine/socat` bridge (`docker-tcp-proxy`, `--network host`,
`--restart unless-stopped`) exposing it on TCP 2375.

The real problem was not the bridge: **WSL2 stops a distro as soon as its last
command exits**, taking the daemon down between - and during - commands. That
produced the intermittent `Connection refused`, `Connection timed out` and
`DOCKER_HOST ... is not listening` symptoms, and is the likely explanation for the
`NoHttpResponseException` on streaming calls recorded in earlier notes: short calls
finished before the shutdown, long-lived ones did not.

**Fix:** hold a session open for the whole run and point Testcontainers at the bridge:

```bash
wsl -d Ubuntu -- bash -c 'sleep 5400'
```

```bash
DOCKER_HOST=tcp://<wsl-ip>:2375 mvn test
```

Take `<wsl-ip>` from the first address of `wsl -d Ubuntu -- hostname -I`, as a literal
IPv4 rather than `localhost` - the JVM may resolve `localhost` to `::1` and report the
port closed. The address is assigned by WSL and changes when the distro restarts, so
re-read it rather than hard-coding it.

### 6.4 Two defects in `EventProcessingFlowIntTest` (test harness)

- **`shouldRecoverStaleDeliveringEvent`** seeded the simulated crashed event with raw
  text as the payload, but the column is `jsonb` -> `invalid input syntax for type json`.
  The production consumer wraps plain text as `{"content": "..."}`; the test now seeds
  it the same way.
- **`shouldFailPermanentlyWhenRetriesAreExhausted`** used `JdbcTemplate.queryForObject`,
  which throws `EmptyResultDataAccessException` rather than an `AssertionError` when the
  row does not exist yet. Awaitility's `untilAsserted` only retries on `AssertionError`,
  so the wait aborted on the first poll - before Kafka had delivered the event. The
  application logs show the event then went through two attempts and ended `FAILED`,
  exactly as specified. Switched to `queryForList` so a missing row is a retryable
  assertion, and raised the timeout to 60s to absorb consumer-group assignment on the
  first test of the class.

---

## 7. Appendix - superseded notes

Earlier revisions of this document concluded that the Windows/WSL path was a dead end
and that the work had to move to a Linux machine with native Docker. That conclusion
was wrong; section 6.3 explains what was really happening. The TCP bridge described in
those notes was sound - it just needed the distro kept alive, and the
`NoHttpResponseException` findings attributed to the Windows/WSL boundary are best
explained by the same cause.
