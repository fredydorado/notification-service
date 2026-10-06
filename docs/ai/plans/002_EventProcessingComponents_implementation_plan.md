# Feature 002 — Event Processing Components: Implementation Plan & Handover

> **Purpose of this file:** the 002 feature implementation is code-complete on the original
> Windows machine, but its integration tests could never be executed there because Docker
> runs inside WSL and Testcontainers cannot reach it reliably (details in §7). The work now
> moves to a **Linux machine with native Docker**, where `mvn test` should finally run the
> whole suite. This document gives a new opencode/agent session (or a human) complete
> context: what was built, what is verified, what is **not yet verified**, and the exact plan
> to finish.
>
> **How to use:** point the agent at this file plus `AGENTS.md` and the rule docs under
> `docs/ai/rules/*.md`. Everything the implementation needs is described below.

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

**All changes for feature 002 are currently in the working tree, uncommitted.** To move to the
Linux machine: commit + push (or copy) the whole repo including untracked files.

---

## 2. What was implemented (code-complete, compiles, 50 unit tests green)

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

**Integration tests — written and compiling, but NEVER executed against a real Docker daemon:**
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

## 3. The plan to execute on the Linux machine

**Goal:** run the full `mvn test` suite against native Docker, fix whatever the integration
tests reveal (they have never run for real), and drive the feature to the 002 spec's
Definition of Done.

### Step 0 — Prerequisites
1. Java 21 + Maven 3.9+ on PATH; native Docker daemon running (`docker info` works as the user
   who runs Maven).
2. Pre-pull to avoid slow first runs: `docker pull postgres:latest` and `docker pull apache/kafka:latest`
   (the user already pulled the Kafka image on the old machine; on Linux pull again locally).
3. Sanity: `mvn -q compile && mvn -q test-compile` (must be clean — it was on Windows).

### Step 1 — Unit tests only (should already be green)
```
mvn test "-Dtest=!NotificationServiceApplicationTests,!*IntTest"
```
Expected: 50 tests, 0 failures. If this fails, something environmental (JDK version) — fix first.

### Step 2 — Persistence integration tests (schema validation happens here)
```
mvn test "-Dtest=SubscriptionPersistenceIntTest,NotificationEventPersistenceIntTest,DeliveryAttemptPersistenceIntTest"
```
These exercise Liquibase migration + `ddl-auto=validate` + claim queries for real. **Likely failure
sources, in order of probability:**
- **Entity↔schema mismatches** under `ddl-auto=validate` (e.g. `webhook_url text`, `event_version`
  default, `payload jsonb`, `next_attempt_at`). Fix by aligning `*Entity.java` column definitions with
  the changelogs — **never** edit an executed changeset without recreating the DB volume (tests
  always start fresh containers, so editing is safe here only while no environment has run them).
- **JPQL claim query** (`findClaimableEvents`) syntax/semantics against real PostgreSQL: enum
  params, `Instant` comparisons, `updatedAt` handling. If Hibernate 6 chokes on the three-status
  `or` predicate, restructure to three separate queries or a native query — keep
  `FOR UPDATE SKIP LOCKED` (`jakarta.persistence.lock.timeout=-2`) and the single-transaction
  claim contract.
- Optimistic-locking/`Thread.sleep(50)` ordering flakiness in `NotificationEventPersistenceIntTest`
  (sleep-based `created_at` ordering) — if flaky, increase the sleep or order by `id`.

### Step 3 — Kafka ingestion integration test
```
mvn test "-Dtest=KafkaNotificationConsumerIntTest"
```
**Likely failure sources:**
- **First-run slowness / await timeouts (30s)**: Kafka container start + topic auto-creation +
  consumer group assignment can exceed awaits on a cold machine. Increase `await()` timeouts to 60s.
- **Topic auto-creation**: publishing uses `KafkaTemplate.send(topicName, ...)`. If the
  `apache/kafka:latest` broker has `auto.create.topics.enable=false`, sends hang/timeout. Fix by
  creating the topic up front in the test (`KafkaAdmin`/`NewTopic` bean or AdminClient) or pulling
  the creation into `@BeforeAll`.
- **`@BeforeAll static` with `@Autowired` parameters** (used in this test for seeding): if the
  JUnit/Spring combination rejects it, switch the class to
  `@TestInstance(Lifecycle.PER_CLASS)` + non-static `@BeforeAll`, or seed in `@BeforeEach` (idempotent
  seeding is already implemented via the `findByClientIdAndEventTypeAndStatus` guard).
- **`await().during(3, SECONDS).atMost(30, SECONDS)`** (duplicate-redelivery test): the `during`
  assertion must stay true for 3s; if it proves flaky, replace with: re-publish, wait fixed 3s,
  assert row count still equals sample size.
- Consumer group stuck offsets between contexts: each test class uses a **unique topic** already
  (`notification-events-ingestion-test` / `-flow-test`); if the shared consumer group commits
  offsets oddly across cached contexts, also set a unique `spring.kafka.consumer.group-id` per class.

### Step 4 — End-to-end flow test
```
mvn test "-Dtest=EventProcessingFlowIntTest"
```
**Likely failure sources:**
- Timing: dispatcher poll 200ms + backoff 100ms should complete each scenario in seconds; if the
  box is slow, bump the class-level test properties (poll-interval/backoff) and the 30s awaits.
- **`shouldRecoverStaleDeliveringEvent`**: the test backdates `updated_at` while the dispatcher may
  already have claimed the just-created DELIVERING event — the claim happens in a tx with SKIP LOCKED;
  if a race makes it flaky, create the stale event with `updated_at` already backdated in the same
  JDBC statement (single UPDATE ... SET status='DELIVERING', updated_at=now()-interval '1 hour').
- The stub `HttpServer` binds `127.0.0.1` — fine on Linux.
- `LAST_BODIES` assertions on `/success`: two tests use `/success` (success test + stale recovery);
  tests run sequentially so the assertion after `awaitEventStatus` is safe, but if the dispatcher
  is still processing a *previous* test's events (claim batch crosses tests), make the body
  assertion content-specific (assert the specific `event_id` — already done) rather than order-dependent.

### Step 5 — Full suite
```
mvn test
```
Includes `NotificationServiceApplicationTests#contextLoads` (default properties: dispatcher
enabled, real Kafka/PG via `@ServiceConnection`) — if it fails, check Kafka listener container
startup (manual ack config) and the `@ConditionalOnProperty` scheduler wiring.

### Step 6 — Map results to the 002 spec "Definition of Done" (§29 of the spec)
Go through the checklist at the end of `docs/spec/features/002_EventProcessingComponents.md`
item by item and note any unmet item. All architectural requirements were implemented on purpose
(event_id identity, ack-after-persistence, durable idempotency, state machine, append-only attempts,
no `deliveries` table, webhook behind `NotificationChannelClient`, `RetryPolicy` isolated,
DB-backed retries, SKIP LOCKED claiming, bounded worker pool, stale DELIVERING recovery,
optimistic locking respected, no tx around HTTP).

### Step 7 — Wrap-up
- Run `mvn -q package` to confirm a full build.
- Commit with a message in the repo's style (the 001 work was committed previously — check
  `git log --oneline -10` for the pattern). Do not commit secrets (there are none).

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

## 6. Current failure signature on the old Windows machine (for contrast)

Every `*IntTest` + `NotificationServiceApplicationTests` failed there with:

```
java.lang.IllegalStateException: Could not find a valid Docker environment.
```

That is **purely environmental** (Docker daemon lives inside a WSL2 distro; the Windows-side JVM
cannot reach it — see §7). It is not a code defect and must not reproduce on Linux with native
Docker. If any Docker-environment-style error appears on Linux, check `docker info` and group
membership (`usermod -aG docker <user>`, re-login) before touching code.

---

## 7. Appendix — Windows/WSL findings (only relevant if anyone returns to that machine)

Docker runs as a native daemon inside the `Ubuntu` WSL2 distro (unix socket only). Findings:

- WSL2 auto-stops the distro when idle → the daemon and any proxy disappear between commands;
  keep a `wsl -d Ubuntu -- bash -c 'sleep infinity'` session alive for the whole `mvn test` run.
- A TCP bridge was installed in WSL for the Windows-side JVM:
  `docker run -d --name docker-tcp-proxy --restart unless-stopped --network host \
     -v /var/run/docker.sock:/var/run/docker.sock alpine/socat \
     TCP-LISTEN:2375,fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock`
  then `DOCKER_HOST=tcp://<wsl-eth0-ip>:2375` (resolve via `wsl -d Ubuntu -- hostname -I`).
- With that bridge: short requests worked, `Testcontainers` connected (`Connected to docker`),
  but **streaming/keep-alive requests via docker-java (Apache HC5) failed with
  `NoHttpResponseException`** on the Windows→WSL path, while the same requests from inside WSL and
  from a plain JDK HttpClient on Windows worked. Root cause not fully isolated (suspect: pooled
  keep-alive connections being closed across the Windows↔WSL boundary). Moving to native Docker
  on Linux sidesteps all of it.
- Use `tcp://<ip>:2375` (explicit IPv4), not `tcp://localhost:2375`: the JVM may resolve
  `localhost` to IPv6 `::1` and report "not listening".
