# Notification Service — Coding Agent Implementation Instructions

## 1. Purpose

This document is an implementation specification for a coding AI agent working inside an existing **Java 21 / Spring Boot notification-service** project.

The agent must generate or modify the application components responsible for:

- Receiving notification events from the event broker.
- Durably persisting notification events before acknowledging the broker message.
- Matching persisted events against active subscriptions.
- Managing notification-event lifecycle/state transitions.
- Creating and processing delivery attempts.
- Delivering notifications through the configured channel.
- Scheduling, dispatching, and recovering retries.
- Handling concurrency and idempotency.

### Explicit scope exclusions

Do **not** implement or redesign:

- REST controllers or REST endpoints.
- REST request/response DTOs unless they are independently required by an existing component.
- Database schema creation.
- Liquibase changelogs.
- JPA repository implementations.
- Database migration scripts.
- Database infrastructure/configuration.

The persistence layer has already been generated. Reuse its existing entities/repositories/contracts.

If the existing persistence implementation is missing a field, query, lock, relationship, enum, or other capability required by this specification, make the **minimum necessary persistence-layer change** to satisfy this specification. Do not redesign the persistence layer unnecessarily.

Regarding the domain model, in this document will be described these components. However, an initial version of the domain model components was also created in the current project. The aim is to attempt to reuse these components and validate whether they align with the requirements defined in this document. If necessary, the minimum changes deemed appropriate will be applied to meet the system design requirements.

---

## 2. Architectural Context

The notification service is responsible for consuming domain events and delivering notifications to subscribed clients.

The relevant logical components are:

```text
Kafka/Event Broker
      |
      v
Event Consumer
      |
      v
Event Processor
      |
      +--> Subscription Matcher
      |
      +--> Notification Event Lifecycle / State Machine
      |
      v
Notification Event Persistence
      |
      v
Retry Dispatcher
      |
      v
Delivery Worker Pool
      |
      v
Webhook Client / Delivery Channel
```

The service owns the notification-processing lifecycle after the source event has been accepted.

The implementation should preserve separation between:

1. **Event ingestion**
2. **Event persistence**
3. **Subscription matching**
4. **Notification-event lifecycle management**
5. **Delivery-attempt execution**
6. **Retry scheduling and dispatch**
7. **External notification delivery**

Use the existing project's architectural/package conventions where they already exist. Do not introduce a second architectural style merely to implement this specification.

---

# 3. Core Domain Concepts

## 3.1 Source Event

A source event is the event received from the broker.

The event must contain, directly or through the existing event envelope:

- `event_id`
- `event_type`
- `event_version`
- `correlation_id`
- event payload

### Event identity

`event_id` is the canonical identity of the notification event.

The design uses:

```text
event_id == notification_event_id
```

Do not generate a different notification-event identifier for the same source event.

### Event version

`event_version` identifies the version of the event contract/schema.

It is **not** the same thing as the database optimistic-lock `version`.

### Correlation ID

`correlation_id` must be propagated through the processing flow and included in logs/tracing context where the project's observability conventions support it.

---

# 4. Event Ingestion

## 4.1 Kafka/Event Consumer

Implement a broker consumer responsible only for receiving the event and delegating processing.

The consumer must not contain the complete business workflow.

Responsibilities:

1. Deserialize/validate the incoming event using the existing project conventions.
2. Extract:
   - `event_id`
   - `event_type`
   - `event_version`
   - `correlation_id`
   - payload
3. Delegate to the application/event-processing use case.
4. Acknowledge the broker message **only after durable persistence has succeeded**.

### Critical reliability rule

The broker message must not be acknowledged before the notification event has been durably persisted.

Conceptually:

```text
consume
  -> validate
  -> persist notification event
  -> acknowledge broker message
```

If persistence fails, the broker message must remain eligible for redelivery according to the existing broker configuration.

Do not acknowledge a message merely because it was received or deserialized successfully.

---

# 5. Idempotent Event Ingestion

The event consumer/processor must be idempotent.

The same source event may be delivered more than once by the broker.

The implementation must ensure that repeated delivery of the same `event_id` does not create duplicate notification-event processing.

Expected behavior:

```text
First event_id
    -> create notification event
    -> continue processing

Repeated event_id
    -> recognize already persisted event
    -> do not create another notification event
    -> do not duplicate delivery processing
```

Use the existing persistence contract/unique constraint to enforce identity.

Do not implement idempotency only with an in-memory cache.

The database remains the durable source of truth for event identity.

---

# 6. Notification Event Processing

Implement an application-level event processor/use case.

Its responsibilities include:

1. Accept the normalized source event.
2. Ensure idempotent persistence.
3. Determine applicable subscriptions.
4. Initiate notification processing for matching subscriptions.
5. Manage notification-event lifecycle transitions.
6. Ensure processing is safe under duplicate messages and concurrent execution.

The processor should orchestrate domain/application services rather than directly contain low-level persistence or HTTP logic.

---

# 7. Subscription Matching

Subscription matching is based on:

```text
(client_id, event_type)
```

Only subscriptions whose status is `ACTIVE` participate in notification delivery.

The current notification channel is:

```text
WEBHOOK
```

The relevant subscription concepts are:

```text
event_type
channel
status
```

Expected subscription status values:

```text
ACTIVE
INACTIVE
```

Expected channel value:

```text
WEBHOOK
```

Do not hard-code subscription matching inside the Kafka consumer.

Create/use a dedicated subscription-matching application/domain component.

---

# 8. Notification Event State Machine

The notification event lifecycle is:

```text
PENDING
   |
   v
DELIVERING
   |
   +--------------------+
   |                    |
   v                    v
COMPLETED         RETRY_SCHEDULED
                        |
                        v
                   DELIVERING
                        |
                        v
                 COMPLETED / RETRY_SCHEDULED / FAILED
```

The defined states are:

```text
PENDING
DELIVERING
RETRY_SCHEDULED
COMPLETED
FAILED
```

## 8.1 Valid transitions

### Initial state

A newly persisted notification event starts as:

```text
PENDING
```

### Delivery start

```text
PENDING -> DELIVERING
```

### Successful delivery

```text
DELIVERING -> COMPLETED
```

### Retryable delivery failure

```text
DELIVERING -> RETRY_SCHEDULED
```

### Permanent/exhausted failure

```text
DELIVERING -> FAILED
```

### Retry dispatch

```text
RETRY_SCHEDULED -> DELIVERING
```

### Replay of a failed event

The design permits:

```text
FAILED -> PENDING
```

when an explicit replay/reprocessing operation is introduced.

Do not implement an arbitrary transition between states.

State transitions should be centralized in a domain/application component rather than scattered throughout controllers, consumers, and workers.

---

# 9. Delivery Attempts

There is intentionally **no separate `deliveries` table**.

The design uses:

```text
notification_event
delivery_attempt
subscription
```

A delivery attempt represents one concrete attempt to deliver a notification.

### Important rule

Retries create **additional delivery-attempt records**.

Do not overwrite a previous attempt.

Example:

```text
delivery_attempt #1 -> FAILED
delivery_attempt #2 -> FAILED
delivery_attempt #3 -> SUCCESS
```

This gives an append-only history of delivery attempts.

Expected delivery-attempt status values:

```text
IN_PROGRESS
SUCCESS
FAILED
```

The current delivery channel is:

```text
WEBHOOK
```

Each delivery attempt must reference the corresponding:

```text
notification_event_id
```

and have a positive:

```text
attempt_number
```

Attempt numbers must increase for subsequent attempts of the same notification event.

---

# 10. Delivery Processing

Implement a dedicated delivery application service/use case.

Its responsibilities are:

1. Obtain a notification event that is ready for delivery.
2. Transition the notification event to `DELIVERING`.
3. Create a new delivery-attempt record.
4. Execute the configured notification channel.
5. Record the delivery result.
6. Transition the notification event according to the result.

The worker must not directly manipulate persistence internals beyond calling the appropriate application/persistence contracts.

---

# 11. Webhook Delivery

The current notification channel is `WEBHOOK`.

Implement the webhook interaction behind a dedicated abstraction, for example:

```text
NotificationChannelClient
```

or an equivalent project-consistent abstraction.

The delivery worker/application service should not depend directly on low-level HTTP client implementation details.

Conceptually:

```text
Delivery Service
      |
      v
Notification Channel Client
      |
      v
External Webhook
```

The client must return a result that allows the application layer to determine whether the delivery:

- succeeded,
- should be retried,
- or failed permanently.

Follow the existing project's HTTP client conventions if they already exist.

---

# 12. Retry Handling

Retries are **database-backed**, not dependent on an in-memory retry queue.

A retryable failure transitions the notification event to:

```text
RETRY_SCHEDULED
```

and stores the next eligible processing time using the existing persistence model.

Conceptually:

```text
DELIVERING
    |
    | retryable failure
    v
RETRY_SCHEDULED
    |
    | next_attempt_at <= now
    v
DELIVERING
```

The retry schedule must survive application restarts.

Do not use an in-memory `ScheduledExecutorService`, local queue, or JVM-only retry state as the source of truth.

---

# 13. Retry Dispatcher

Implement a dedicated retry-dispatching component.

Its responsibility is to periodically locate retryable notification events whose retry time has arrived.

Conceptually:

```text
Retry Dispatcher
      |
      | query due RETRY_SCHEDULED events
      v
Claim events
      |
      v
Transition to DELIVERING
      |
      v
Submit to Delivery Worker Pool
```

The dispatcher must be safe when multiple service instances are running.

## 13.1 Concurrent claiming

The database-backed design uses row-level locking with:

```text
FOR UPDATE SKIP LOCKED
```

where supported by the existing persistence implementation.

The purpose is to ensure that two dispatcher instances do not process the same retryable event concurrently.

The persistence layer should expose an appropriate transactional claim operation.

The coding agent should add/adjust the persistence query if the already-generated persistence layer does not provide the required capability.

---

# 14. Delivery Worker Pool

Delivery execution should be performed by a worker pool rather than synchronously inside the retry scheduler.

Conceptually:

```text
Retry Dispatcher
       |
       v
Worker Pool
   |   |   |
   v   v   v
Webhook deliveries
```

The worker pool should:

- limit concurrent external deliveries,
- prevent an unbounded number of HTTP calls,
- isolate slow external endpoints from the dispatcher,
- allow multiple notification events to be processed concurrently.

Use the project's existing task-execution conventions if available.

Do not create an uncontrolled thread per notification event.

---

# 15. Retry Scheduling Policy

The implementation must isolate retry-policy decisions behind a dedicated component, for example:

```text
RetryPolicy
```

or an equivalent name.

The retry policy determines:

- whether a failure is retryable,
- whether the maximum retry/attempt limit has been reached,
- the next retry time/backoff.

Do not scatter retry calculations throughout the webhook client, worker, dispatcher, and state machine.

The implementation should make the retry policy replaceable/testable.

Use the retry/backoff values defined by the existing project configuration or design. Do not introduce arbitrary hard-coded business values when configuration already exists.

---

# 16. Retryable vs Permanent Failures

The delivery processing flow must distinguish between:

### Retryable failure

Result:

```text
delivery_attempt -> FAILED
notification_event -> RETRY_SCHEDULED
```

and a future retry is scheduled.

### Permanent failure / exhausted retries

Result:

```text
delivery_attempt -> FAILED
notification_event -> FAILED
```

No further automatic retry should be scheduled.

The decision must be centralized in the retry policy/application service.

---

# 17. Stale `DELIVERING` Recovery

The design must account for application crashes during delivery.

Example:

```text
notification_event = DELIVERING
application crashes
```

The event must not remain permanently stuck.

Implement recovery for stale `DELIVERING` work using the existing persistence capabilities.

The recovery mechanism should identify deliveries that have remained in `DELIVERING` beyond the configured processing timeout/lease period and make them eligible for continued processing according to the retry policy.

The implementation must preserve idempotency and optimistic locking while recovering stale work.

Do not silently mark stale events as `COMPLETED`.

---

# 18. Optimistic Concurrency Control

The persistence model includes a database `version` field for optimistic locking.

Use optimistic locking for state changes where the existing persistence model supports it.

The application must assume that two application instances may attempt to process the same notification event.

Example:

```text
Worker A -> DELIVERING
Worker B -> DELIVERING
```

Only one valid state transition should succeed.

A stale update must not overwrite a newer state.

If an optimistic-lock conflict occurs:

1. Do not overwrite the newer state.
2. Reload/re-evaluate when appropriate.
3. Treat the already-successful/concurrent transition as authoritative.
4. Avoid creating duplicate delivery work.

Do not disable optimistic locking to make concurrent processing "simpler."

---

# 19. Transaction Boundaries

Keep transactional operations small and explicit.

A transaction should be used when atomically performing related database state changes, such as:

```text
claim notification event
+
transition state
+
create delivery attempt
```

Do not hold a database transaction open while performing an external webhook HTTP request.

Avoid:

```text
BEGIN TRANSACTION
    call external webhook
    wait for response
    update database
COMMIT
```

Instead, use database transactions around state/claim operations and perform external I/O outside long-running database transactions.

The exact transaction annotations/configuration should follow the existing project's Spring conventions.

---

# 20. Recommended Application Component Responsibilities

The coding agent should create or adapt components equivalent to the following responsibilities.

Names may follow the existing project's naming conventions.

### Event Consumer

Responsibilities:

- Consume broker messages.
- Deserialize/validate event.
- Propagate correlation ID.
- Delegate to event-processing use case.
- Acknowledge only after durable persistence.

Must not:

- contain subscription matching logic,
- contain webhook HTTP logic,
- implement retry scheduling.

### Event Processor

Responsibilities:

- Orchestrate notification event ingestion.
- Enforce idempotency.
- Persist notification events.
- Initiate subscription matching and processing.

### Subscription Matcher

Responsibilities:

- Find active subscriptions matching the event type/client context.
- Return applicable subscriptions.
- Keep matching rules isolated from delivery execution.

### Notification Event State Manager

Responsibilities:

- Enforce valid state transitions.
- Encapsulate lifecycle rules.
- Prevent illegal state changes.

### Delivery Service

Responsibilities:

- Start a delivery.
- Create delivery attempts.
- Invoke the channel client.
- Record results.
- Apply retry policy.
- Transition notification-event state.

### Retry Policy

Responsibilities:

- Determine retryability.
- Determine whether attempts are exhausted.
- Calculate next retry time/backoff.

### Retry Dispatcher

Responsibilities:

- Find due retryable events.
- Claim them safely.
- Move them into delivery processing.
- Submit work to the worker pool.
- Recover stale work according to the design.

### Delivery Worker

Responsibilities:

- Execute a delivery task.
- Delegate actual channel communication to the channel client.
- Return/record the delivery result.
- Avoid owning scheduling responsibilities.

### Webhook Client

Responsibilities:

- Perform the external webhook HTTP call.
- Convert the HTTP/client result into a delivery result understood by the application layer.
- Avoid changing notification-event state directly.

---

# 21. Persistence Contracts Expected by the Processing Layer

The coding agent must reuse the generated persistence layer.

The application layer may require persistence operations equivalent to:

```text
find notification event by event_id
save notification event
find active subscriptions matching event type
create delivery attempt
find due RETRY_SCHEDULED notification events
atomically claim due retry events
find/recover stale DELIVERING events
update notification-event state with optimistic locking
find next delivery attempt number
```

These are **logical capabilities**, not instructions to create a specific repository API.

Use the existing repository interfaces and naming conventions where possible.

If a required capability is absent, make the smallest necessary change to the generated persistence layer.

Do not create a new `deliveries` entity/table.

---

# 22. Persistence Model Contract

The existing persistence layer is based on three tables:

```text
subscription
notification_event
delivery_attempt
```

There is no `deliveries` table.

The processing implementation must respect the following domain contract.

## subscription

Relevant concepts:

```text
event_type
channel
status
version
```

Enum-like values:

```text
channel:
    WEBHOOK

status:
    ACTIVE
    INACTIVE
```

## notification_event

Relevant concepts:

```text
event_id / notification_event_id
event_type
event_version
correlation_id
status
payload
version
```

Enum-like status values:

```text
PENDING
DELIVERING
RETRY_SCHEDULED
COMPLETED
FAILED
```

## delivery_attempt

Relevant concepts:

```text
notification_event_id
channel
status
attempt_number
version
```

Enum-like values:

```text
channel:
    WEBHOOK

status:
    IN_PROGRESS
    SUCCESS
    FAILED
```

Do not duplicate these concepts in another persistence model unless the existing project architecture requires an explicit domain mapping.

---

# 23. Event Processing Flow

The normal flow should behave conceptually as follows:

```text
1. Broker publishes event
          |
          v
2. Event Consumer receives event
          |
          v
3. Validate/normalize event
          |
          v
4. Persist notification_event
          |
          v
5. Acknowledge broker message
          |
          v
6. Match ACTIVE subscription
          |
          v
7. Notification event becomes PENDING
          |
          v
8. Delivery processing claims event
          |
          v
9. PENDING -> DELIVERING
          |
          v
10. Create delivery_attempt
          |
          v
11. Execute WEBHOOK
       /       \
      /         \
 success       failure
   |              |
   v              v
COMPLETED     retry policy
                  |
            +-----+------+
            |            |
          retry       exhausted
            |            |
            v            v
      RETRY_SCHEDULED  FAILED
            |
            v
      Retry Dispatcher
            |
            v
       DELIVERING
```

The exact ordering of post-persistence subscription processing may follow the existing application architecture, but the broker acknowledgment must remain dependent on durable event persistence.

---

# 24. Error Handling

Errors must be classified according to where they occur.

## Deserialization/validation failure

Follow the existing Kafka error-handling/DLQ conventions if already configured by the project.

Do not acknowledge invalid messages merely to hide processing failures.

## Persistence failure

Do not acknowledge the broker message.

Allow normal broker redelivery according to the configured consumer semantics.

## Subscription lookup failure

Treat as an application processing failure. Do not falsely mark the notification event as successfully delivered.

## Webhook failure

Delegate retryability to the retry policy.

## Unexpected worker exception

Ensure the notification event and delivery attempt do not remain permanently stuck in an inconsistent state.

Unexpected failures must be observable and must participate in the stale-work recovery mechanism where applicable.

---

# 25. Logging and Observability

Processing components should emit structured logs using the project's existing logging conventions.

At minimum, the following identifiers should be available in relevant logs:

- `event_id`
- `notification_event_id`
- `event_type`
- `event_version`
- `correlation_id`
- `attempt_number`
- `channel`
- notification-event status
- delivery-attempt status

Avoid logging full notification payloads when they may contain sensitive or unnecessarily large data.

Useful lifecycle log events include:

```text
event received
event persisted
duplicate event detected
subscription matched
notification delivery started
delivery attempt created
delivery succeeded
delivery failed
retry scheduled
retry dispatched
notification failed permanently
stale delivery recovered
optimistic locking conflict
```

---

# 26. Testing Requirements

The coding agent must generate tests for the business behavior, not only line coverage.

## Event ingestion tests

Verify:

- New event is persisted.
- Broker acknowledgment occurs only after persistence succeeds.
- Duplicate `event_id` does not create duplicate notification processing.
- Event metadata is preserved.
- `event_id == notification_event_id`.

## Subscription matching tests

Verify:

- Active matching subscriptions are selected.
- Inactive subscriptions are ignored.
- Non-matching event types are ignored.
- WEBHOOK channel is handled correctly.

## State-machine tests

Verify all valid transitions:

```text
PENDING -> DELIVERING
DELIVERING -> COMPLETED
DELIVERING -> RETRY_SCHEDULED
DELIVERING -> FAILED
RETRY_SCHEDULED -> DELIVERING
FAILED -> PENDING
```

Also verify invalid transitions are rejected.

## Delivery tests

Verify:

- A delivery attempt is created for each actual attempt.
- Successful delivery produces `SUCCESS`.
- Failed delivery produces `FAILED`.
- Successful delivery ends with notification event `COMPLETED`.

## Retry tests

Verify:

- Retryable failures become `RETRY_SCHEDULED`.
- Next retry time is calculated by the retry policy.
- Exhausted retries become `FAILED`.
- A retry creates a new delivery attempt.
- Previous delivery-attempt records are not overwritten.
- Due retries are dispatched.
- Multiple dispatcher instances cannot claim the same work concurrently.

## Concurrency tests

Verify:

- Optimistic-lock conflicts do not overwrite newer state.
- Concurrent processing does not produce duplicate delivery.
- Retry claiming is safe across multiple workers/instances.
- Stale `DELIVERING` work can be recovered.

Use the project's existing testing stack. If integration tests already use Testcontainers/PostgreSQL, extend those tests for locking/transactional behavior rather than replacing them with mocks.

---

# 27. Code Quality Requirements

The generated implementation must:

- Target Java 21.
- Follow existing Spring Boot project conventions.
- Prefer constructor injection.
- Keep business rules outside controllers/adapters.
- Keep broker-specific concerns inside the messaging adapter.
- Keep HTTP-specific concerns inside the webhook/channel adapter.
- Keep persistence-specific concerns inside persistence adapters/repositories.
- Keep retry policy independent from transport implementation.
- Avoid static/global mutable state.
- Avoid duplicated state-transition logic.
- Avoid arbitrary sleeps for retry processing.
- Avoid in-memory retry state as the source of truth.
- Avoid creating a `deliveries` table/entity.
- Avoid long-running database transactions around external HTTP calls.

Use interfaces at architectural boundaries when they improve separation and testability, consistent with the existing project style.

---

# 28. Implementation Constraints for the Coding Agent

Before generating code:

1. Inspect the existing project structure.
2. Identify existing domain, application, messaging, persistence, and infrastructure packages.
3. Reuse existing abstractions where they match this specification.
4. Inspect the already-generated persistence entities and repositories.
5. Identify missing persistence capabilities required by this document.
6. Make only the minimum persistence changes required.
7. Do not generate REST endpoint implementation.
8. Do not generate Liquibase/database schema implementation.
9. Do not introduce a `deliveries` table/entity.
10. Implement the event-processing and retry components described above.
11. Add unit/integration tests for the behavior.
12. Preserve existing APIs and unrelated functionality.

If an existing class already provides one of the responsibilities described here, extend/refactor it rather than creating a duplicate implementation.

---

# 29. Definition of Done

The implementation is complete when:

- [ ] Broker events are consumed through the existing messaging infrastructure.
- [ ] `event_id` is used as `notification_event_id`.
- [ ] `event_version` and `correlation_id` are preserved.
- [ ] Event persistence occurs before broker acknowledgment.
- [ ] Event ingestion is idempotent.
- [ ] Active subscriptions are matched by the defined event/subscription criteria.
- [ ] Notification-event state transitions follow the defined state machine.
- [ ] Delivery attempts are append-only.
- [ ] Retries create new delivery attempts.
- [ ] No `deliveries` table/entity is introduced.
- [ ] Webhook delivery is isolated behind a channel/client abstraction.
- [ ] Retry decisions are isolated in a retry policy.
- [ ] Retry scheduling is database-backed.
- [ ] Due retry work can be safely claimed concurrently.
- [ ] `FOR UPDATE SKIP LOCKED` or the project's equivalent concurrency mechanism is used where required by the persistence implementation.
- [ ] Delivery execution uses a bounded worker pool.
- [ ] Stale `DELIVERING` work can be recovered.
- [ ] Optimistic locking is respected.
- [ ] External HTTP calls are not executed inside long-running database transactions.
- [ ] REST endpoint implementation is not changed/generated as part of this task.
- [ ] Database/Liquibase implementation is not generated as part of this task.
- [ ] Existing persistence components are reused and changed only when required.
- [ ] Unit and integration tests cover event ingestion, state transitions, delivery, retry, idempotency, and concurrency behavior.
