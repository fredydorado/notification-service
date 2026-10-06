# REST API layer for notification-service (spec 003)

## Context

`docs/spec/features/003_RestNotificationService.md` asks for the REST/API layer of the
notification service: three endpoints over notification events, standardized errors, OpenAPI
docs on controller interfaces, and tests. Everything behind the API — Kafka ingestion,
subscription matching, the delivery worker pool, retry policy, webhook delivery and the
notification-event state machine — already exists (features 001 and 002) and must be reused,
not reimplemented.

The REST layer is genuinely greenfield: there is no `adapter/in/web` package, no controller,
no DTO, no `@RestControllerAdvice`, no Spring Security, and no correlation-ID filter anywhere
in the project. `spring-boot-starter-webmvc`, `springdoc-openapi-starter-webmvc-ui:3.1.0` and
MapStruct are already on the classpath, so no new infrastructure is needed beyond the
validation starter.

Three things in the spec do not match the code as it stands, and were resolved with the user:

| Spec says | Reality | Decision |
|---|---|---|
| `lastHttpStatus` / per-attempt `httpStatus` | No `http_status` column, no domain field, `WebhookDeliveryResult` carries only an outcome enum + message | **Add it end-to-end.** The Liquibase changesets have never been run against any database, so the existing changeset is edited in place rather than adding a migration. |
| "authenticated principal determines `client_id`" | No Spring Security, no auth of any kind | **`X-Client-Id` request header.** No security framework is introduced; `client_id` is never a query param and ownership is enforced in the application service. Easy to swap for real auth later. |
| `{notification_event_id}` shown as a UUID | IDs are `Long` surrogate PKs plus a `String eventId` business key (`EVT001`); the domain documents `event_id == notification_event_id` | **Use the `String` eventId.** The surrogate `Long` stays out of the public API. |

Outcome: three working endpoints that read and replay notification events scoped to the calling
client, with the delivery pipeline untouched except for the HTTP-status capture.

---

## Endpoints

| Method | Path | Success |
|---|---|---|
| `GET` | `/notification_events` | `200` |
| `GET` | `/notification_events/{notification_event_id}` | `200` |
| `POST` | `/notification_events/{notification_event_id}/replay` | `202` |

Paths and query params are snake_case; JSON bodies are camelCase (as the spec's examples show).

---

## 1. Capture the HTTP status (delivery pipeline — minimal, surgical)

Ordered so the compiler walks you through every call site.

1. **`src/main/resources/db/changelog/v1.0/003-create-delivery-attempt.yaml`** — add a nullable
   `http_status` column of type `integer` after `error_message`, plus a `COMMENT ON COLUMN`
   in the style already used in that file. Edit in place (scripts have never been executed).
2. **[DeliveryAttemptEntity.java](src/main/java/com/fardorado/notification/adapter/out/persistence/entity/DeliveryAttemptEntity.java)** —
   `@Column(name = "http_status") private Integer httpStatus;`
3. **[DeliveryAttempt.java](src/main/java/com/fardorado/notification/domain/model/delivery/DeliveryAttempt.java)** —
   add `private Integer httpStatus;` + getter, add it to the reconstitution constructor, and widen
   the two transitions:
   - `markSuccess(Integer httpStatus, Instant completedAt)`
   - `markFailed(String errorMessage, Integer httpStatus, Instant completedAt)`
4. **[WebhookDeliveryResult.java](src/main/java/com/fardorado/notification/application/port/out/WebhookDeliveryResult.java)** —
   add an `Integer httpStatus` component; update `success`, `retryableFailure`, `permanentFailure`
   to take it. `null` means "no response was received" (connect/timeout/transport failure).
5. **[WebhookChannelClientImpl.java](src/main/java/com/fardorado/notification/adapter/out/webhook/WebhookChannelClientImpl.java)** —
   pass the real status code through; `null` on exception paths.
6. **[DeliveryResultService.java](src/main/java/com/fardorado/notification/application/service/DeliveryResultService.java)** —
   forward `result.httpStatus()` into `markSuccess` / `markFailed`.
7. **[DeliveryClaimService.java](src/main/java/com/fardorado/notification/application/service/DeliveryClaimService.java)** —
   the stale-recovery path calls `markFailed(..., null, ...)`.

`DeliveryAttemptPersistenceMapper` needs no change (names match). Existing tests that call these
methods must be updated: `DeliveryAttemptTest`, `ProcessDeliveryUseCaseImplTest`,
`DeliveryClaimServiceTest`, `WebhookChannelClientImplTest`, and `FakeDeliveryAttemptRepository`.

### Also add `createdAt` to the domain event

`notification_event.created_at` exists in the DB and on the entity but not on the domain
`NotificationEvent`, and the API must expose it and filter on it. Add
`private final Instant createdAt;` + getter to the reconstitution constructor
([NotificationEvent.java](src/main/java/com/fardorado/notification/domain/model/notification/NotificationEvent.java));
`newEvent(...)` passes `null`. In `NotificationEventPersistenceMapper`, explicitly
`@Mapping(target = "createdAt", ignore = true)` and `@Mapping(target = "updatedAt", ignore = true)`
on `toEntity` so the Hibernate-generated timestamps are never overwritten.

Finally, add `idx_notification_event_subscription_id` to
`002-create-notification-event.yaml` — every client-scoped query joins on that column and there
is no index on it today.

---

## 2. Application layer

Ownership has no shortcut: `notification_event` has **no `client_id`**. The only path is
`notification_event.subscription_id → subscription.client_id`. Accepted consequence: events that
failed via `markUnmatched()` have `subscription_id = NULL` and are therefore invisible to every
client. That is correct — they have no subscription and so no webhook to replay to.

**New read models — `application/result/`** (records; `Result` suffix per NAMING_CONVENTIONS):
- `PagedResult<T>(List<T> items, int page, int size, long totalElements, int totalPages)`
- `NotificationEventSummaryResult(String id, EventType eventType, Instant createdAt, NotificationEventStatus status, int attemptCount, Integer lastHttpStatus)`
- `NotificationEventDetailResult(... summary fields ..., String webhookUrl, List<DeliveryAttemptResult> attempts)`
- `DeliveryAttemptResult(int attempt, DeliveryAttemptStatus status, Integer httpStatus)`
- `ReplayNotificationEventResult(String notificationEventId, NotificationEventStatus status)`

**New commands — `application/command/`:**
- `ListNotificationEventsCommand(String clientId, Instant from, Instant to, NotificationEventStatus deliveryStatus, int page, int size)`
- `GetNotificationEventCommand(String clientId, String eventId)`
- `ReplayNotificationEventCommand(String clientId, String eventId)`

**New input ports — `application/port/in/`:** `ListNotificationEventsUseCase`,
`GetNotificationEventUseCase`, `ReplayNotificationEventUseCase`.

**New exceptions — `application/exception/`:** `ErrorCode` (enum: `RESOURCE_NOT_FOUND`,
`INVALID_REQUEST`, `VALIDATION_ERROR`, `RESOURCE_CONFLICT`, `NOTIFICATION_EVENT_NOT_REPLAYABLE`,
`UNAUTHORIZED`, `INTERNAL_ERROR`), `ResourceNotFoundException`, `InvalidRequestException`,
`ResourceConflictException`, and `NotificationEventNotReplayableException extends
ResourceConflictException` carrying the `NOTIFICATION_EVENT_NOT_REPLAYABLE` code.

**Two new methods on the existing output port**
[NotificationEventRepository.java](src/main/java/com/fardorado/notification/application/port/out/NotificationEventRepository.java)
(spec §27's "minimum necessary change"):

```java
Optional<NotificationEvent> findByEventIdAndClientId(String eventId, String clientId);

PagedResult<NotificationEventSummaryResult> findSummariesByClientId(
        String clientId, Instant from, Instant to,
        NotificationEventStatus status, int page, int size);
```

**New use case implementations — `application/service/`:**

- `ListNotificationEventsUseCaseImpl` — validates `from <= to` and `size <= maxPageSize`
  (throwing `InvalidRequestException` → 400), then delegates to `findSummariesByClientId`.
- `GetNotificationEventUseCaseImpl` — `findByEventIdAndClientId` (empty → `ResourceNotFoundException`,
  which is also what a foreign client's event yields, so existence is never leaked), then composes
  the detail from **existing ports**: `SubscriptionRepository.findById(event.getSubscriptionId())`
  for `webhookUrl` and
  `DeliveryAttemptRepository.findByNotificationEventIdOrderByAttemptNumber(event.getId())` for the
  attempt history, `attemptCount` and `lastHttpStatus`.
- `ReplayNotificationEventUseCaseImpl` — `@Transactional`; load via `findByEventIdAndClientId`
  (empty → 404), call the **already-existing** `NotificationEvent.replay()`
  (`FAILED → PENDING`, currently unreferenced in production code — it was written for exactly this),
  then `save`. Translate `InvalidStatusTransitionException` → `NotificationEventNotReplayableException`
  (409) and `OptimisticLockingFailureException` → `ResourceConflictException` (409), so two
  concurrent replays yield one `202` and one `409`.

No dispatcher call is needed: once the row is `PENDING`, the existing
`NotificationEventDispatchScheduler` claims it on its next sweep. That is what makes replay
asynchronous with zero new infrastructure.

---

## 3. Web adapter — `adapter/in/web/`

Layout follows PROJECT_GUIDELINES §2 and ARCHITECTURE_CONVENTIONS §4: interface carries all the
OpenAPI annotations, implementation sits in a sibling `impl` package.

- `NotificationEventController` — interface. `@Tag`, and per method `@Operation`,
  `@ApiResponses` with `@Content(schema = @Schema(implementation = ErrorResponseDto.class))` for
  400/401/404/409/500, `@Parameter` on each path/query param. **No OpenAPI annotations on the impl.**
- `impl/NotificationEventControllerImpl` — `@RestController`,
  `@RequestMapping("/notification_events")`, `@Validated`, constructor injection of the three use
  cases and the MapStruct mapper. Each method maps params → command, calls the use case, maps the
  result → DTO, returns a `ResponseEntity`. Nothing else.
- `dto/` — `NotificationEventSummaryResponseDto`, `NotificationEventDetailResponseDto`,
  `DeliveryAttemptResponseDto`, `ReplayNotificationEventResponseDto`, `PagedResponseDto<T>`,
  `ErrorResponseDto`, `FieldErrorDto`. All records.
- `mapper/NotificationEventWebMapper` — MapStruct `@Mapper(componentModel = "spring")`, mapping
  application results → response DTOs (MapStruct is mandatory for cross-layer mapping,
  PROJECT_GUIDELINES §3, and is already wired in both compiler executions).
- `advice/GlobalExceptionHandler` — `@RestControllerAdvice`.
- `filter/CorrelationIdFilter` — `OncePerRequestFilter` reading `X-Correlation-Id` (the same header
  [WebhookChannelClientImpl.java](src/main/java/com/fardorado/notification/adapter/out/webhook/WebhookChannelClientImpl.java)
  already sends outbound), generating a UUID when absent, putting it in MDC under `correlationId`
  (the key `ProcessDeliveryUseCaseImpl` already uses), echoing it on the response, clearing in
  `finally`. This is the "smallest component necessary" the spec allows — do not invent a second
  mechanism.

### Controller signature for the list endpoint

Query params are snake_case while DTO fields are camelCase, so bind them explicitly rather than
via `@ModelAttribute`:

```java
ResponseEntity<PagedResponseDto<NotificationEventSummaryResponseDto>> listNotificationEvents(
        @RequestHeader("X-Client-Id") @NotBlank String clientId,
        @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
        @RequestParam(name = "delivery_status", required = false) NotificationEventStatus deliveryStatus,
        @RequestParam(defaultValue = "0") @PositiveOrZero int page,
        @RequestParam(defaultValue = "20") @Positive int size);
```

`delivery_status` binds straight onto the existing `NotificationEventStatus` enum — no duplicate
API enum (spec §21).

### Exception mapping

| Exception | Status | `code` |
|---|---|---|
| `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `InvalidRequestException` | 400 | `INVALID_REQUEST` |
| `ResourceConflictException` (incl. not-replayable) | 409 | `RESOURCE_CONFLICT` / `NOTIFICATION_EVENT_NOT_REPLAYABLE` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` + `errors[]` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` + `errors[]` |
| `MethodArgumentTypeMismatchException` | 400 | `VALIDATION_ERROR` (bad enum / bad date format) |
| `MissingRequestHeaderException` | 401 | `UNAUTHORIZED` (no `X-Client-Id`) |
| `Exception` | 500 | `INTERNAL_ERROR`, generic message, full stack trace logged only |

`ErrorResponseDto` is populated with `path` from the request and `correlationId` from MDC. No stack
traces, SQL, schema details or internal class names reach the client.

Note: `DeliveryResultService` currently *swallows* `InvalidStatusTransitionException` in the
delivery path — that behaviour must not change. Only the replay use case translates it.

---

## 4. Persistence adapter

- **[NotificationEventJpaRepository.java](src/main/java/com/fardorado/notification/adapter/out/persistence/repository/NotificationEventJpaRepository.java)** —
  `findByEventIdAndClientId` is JPQL with an explicit join, because
  `NotificationEventEntity.subscriptionId` is a **plain `Long` scalar, not a `@ManyToOne`**,
  so there is no association to navigate.

  For the paged list the repository also extends `JpaSpecificationExecutor`.

  > **Implementation note (changed during build).** The plan originally called for a single
  > query with `(:param is null or ...)` branches for the optional filters. That does not work
  > on PostgreSQL: the untyped null bind makes the driver fail with
  > `could not determine data type of parameter $N`. The filters are therefore built as a
  > Spring Data `Specification` (`NotificationEventRepositoryImpl.ownedBy`), so an unsupplied
  > filter never reaches the SQL at all. Ownership is a subquery over `subscription`, and
  > `Page`/count come for free.

- **[NotificationEventRepositoryImpl.java](src/main/java/com/fardorado/notification/adapter/out/persistence/repository/NotificationEventRepositoryImpl.java)** —
  implements the two new port methods and converts the Spring Data `Page` into the project's
  own `PagedResult`, so Spring Data types never cross the port boundary (spec §23,
  ARCHITECTURE_CONVENTIONS §3). Attempt counts and last HTTP statuses for the page are
  fetched in **one** additional query
  (`findByNotificationEventIdInOrderByAttemptNumberAsc`) and grouped in memory, so the query
  count stays constant regardless of page size.

---

## 5. Configuration

- **`pom.xml`** — add `spring-boot-starter-validation` (hibernate-validator is only transitive via
  data-jpa today; the per-technology starter pattern in `AGENTS.md` wants it explicit). No other
  dependency, and **no second OpenAPI library**.
- **`configuration/NotificationApiProperties`** — `@ConfigurationProperties("notification.api")`
  with `defaultPageSize` (20) and `maxPageSize` (100), registered alongside
  `NotificationProcessingProperties`.
- **`application.yaml`** — add the `notification.api.*` block, and a `logging.pattern.level`
  including `%X{correlationId:-}` so the MDC value the filter sets is actually visible (today
  nothing prints it).
- Optional, low cost: a minimal `OpenApiConfiguration` `@Bean OpenAPI` for title/version. Do not
  create a second springdoc configuration.

---

## 6. Tests

House style, confirmed from the existing suite: **JUnit 5 + AssertJ only, no Mockito anywhere, no
MockMvc.** Unit tests use the hand-written fakes in `application/service`
(`FakeNotificationEventRepository` et al.) — these must be extended with the two new port methods.
Integration tests are `*IntTest` in the root `com.fardorado.notification` package,
`@Import(TestcontainersConfiguration.class)` + `@SpringBootTest(webEnvironment = RANDOM_PORT)`,
calling the API with `TestRestTemplate` from `org.springframework.boot.resttestclient`.

> **Implementation note (changed during build).** In Boot 4 `TestRestTemplate` is **not**
> auto-registered for `RANDOM_PORT`: the test class needs `@AutoConfigureTestRestTemplate`,
> and `spring-boot-restclient` must be added as a test dependency for `RestTemplateBuilder`.
> Without each of these the context fails — with *No qualifying bean of type
> 'TestRestTemplate'* and `NoClassDefFoundError: .../RestTemplateBuilder` respectively.

**Unit tests** (`application/service`): `ListNotificationEventsUseCaseImplTest` (pagination bounds,
`from > to` → `InvalidRequestException`, `size > max` → `InvalidRequestException`),
`GetNotificationEventUseCaseImplTest` (composition of detail, unknown/foreign event → 404),
`ReplayNotificationEventUseCaseImplTest` (`FAILED` → `PENDING`; `COMPLETED`/`DELIVERING`/`PENDING`/
`RETRY_SCHEDULED` → not-replayable; optimistic-lock failure → conflict, via the fake's `onSave` hook).

**`NotificationEventControllerIntTest`** — set `notification.processing.dispatch-enabled=false` so
the scheduler does not race the assertions (the established pattern). Cover:
- list: pagination envelope, `from`/`to`/`delivery_status` filters, `attemptCount` and
  `lastHttpStatus` populated;
- **ownership isolation**: client B gets 404 for client A's event and never sees it in the list;
- detail: 200 with `webhookUrl` + ordered `attempts[]`; 404 unknown;
- replay: 202 with `{notificationEventId, status: "PENDING"}` and the row actually `PENDING`;
  409 on a `COMPLETED` event; 404 unknown; two concurrent replays → exactly one 202;
- errors: missing `X-Client-Id` → 401; `page=-1`, `size=0`, `size` over max, `from > to`,
  malformed date, unknown `delivery_status` → 400 with a well-formed `ErrorResponseDto`;
- `ErrorResponseDto` leaks no stack trace or internal class name.

Seed data through the real output ports (`subscriptionRepository.save(Subscription.newSubscription(...))`),
assert raw columns with the autowired `JdbcTemplate`, and use unique ids per test — there is no
cleanup between tests.

**Extend `EventProcessingFlowIntTest`** with one assertion that `delivery_attempt.http_status` is
persisted (e.g. `404` on the `/not-found` stub, `200` on `/success`).

---

## Verification

```bash
mvn -o compile
```

Unit tests only — no Docker needed:

```bash
mvn test "-Dtest=!*IntTest,!NotificationServiceApplicationTests" -DfailIfNoSpecifiedTests=false
```

Full suite (Docker in WSL2 on this machine — use the runner script, never bare `mvn test`):

```bash
./scripts/run-tests.ps1 test
```

Narrow to the new integration test:

```bash
./scripts/run-tests.ps1 test -Dtest=NotificationEventControllerIntTest
```

Manual smoke check — `ddl-auto: validate` means a mismatch between the edited changeset and
`DeliveryAttemptEntity` fails the context at startup, so a clean boot is itself a schema check.
Run with containers via `TestNotificationServiceApplication`, then:

```bash
curl -i -H 'X-Client-Id: CLIENT001' 'http://localhost:8080/notification_events?page=0&size=20'
```

Confirm Swagger UI lists all three endpoints with the documented responses at
`http://localhost:8080/swagger-ui.html`.

---

## Out of scope (explicitly not touched)

Kafka consumer and ingestion, subscription matching, the delivery worker pool, retry dispatcher,
retry policy, webhook delivery execution, and the notification-event state machine. No `deliveries`
table. No new persistence model. The only changes to the delivery pipeline are the HTTP-status
plumbing in §1.

---

## Outcome

Implemented and verified. The suite went from **80 to 123 tests, all passing**
(`./scripts/run-tests.ps1 clean test`): 20 new application unit tests and 23 new REST
integration tests, with the existing 80 kept green.

Two things changed from the plan as written; both are flagged inline above:

1. The paged query is a Spring Data `Specification`, not a native query with
   `(:param is null or ...)` branches, which PostgreSQL rejects.
2. `TestRestTemplate` needed `@AutoConfigureTestRestTemplate` plus a
   `spring-boot-restclient` test dependency, neither of which the plan anticipated.

Everything else landed as planned, including the end-to-end `http_status` capture (the
Liquibase changeset was edited in place, since it had never been run) and the `X-Client-Id`
ownership model.
