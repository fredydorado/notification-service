# Database Conventions

## 1. Purpose

This document defines the database design and persistence conventions for the `notification-service`.

It is intended to be used by developers and AI coding agents when creating or modifying:

* Liquibase database migrations
* Database tables and constraints
* JPA persistence entities
* Repositories
* Database indexes
* Persistence integration tests

The database design must remain consistent with the service's C4 architecture and domain model.

---

## 2. Database Technology

The service uses:

* **Database:** PostgreSQL
* **Migration tool:** Liquibase
* **Persistence framework:** Spring Data JPA / Hibernate
* **Java:** Java 21

Liquibase is the **authoritative source of truth for the database schema**.

Hibernate must not create or modify the database schema.

Recommended configuration:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

Production environments must never use:

```properties
spring.jpa.hibernate.ddl-auto=create
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.hibernate.ddl-auto=update
```

---

# 3. Database Model

The notification service contains the following tables:

1. `subscription`
2. `notification_event`
3. `delivery_attempt`

There is intentionally **no `delivery` table**.

`delivery_attempt` represents each attempt to deliver a notification. Retries result in additional records in `delivery_attempt`.

The database model must not introduce additional tables unless the domain model and architecture are explicitly changed.

---

# 4. Liquibase Conventions

## 4.1 Liquibase Directory Structure

Liquibase changelogs must be versioned.

Recommended structure:

```text
src/main/resources/
└── db/
    └── changelog/
        ├── db.changelog-master.yaml
        │
        ├── v1.0/
        │   ├── db.changelog-v1.0.yaml
        │   ├── 001-create-subscription.yaml
        │   ├── 002-create-notification-event.yaml
        │   └── 003-create-delivery-attempt.yaml
        │
        └── v1.1/
            └── ...
```

The master changelog should include version-specific changelogs.

Example:

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/v1.0/db.changelog-v1.0.yaml
```

---

## 4.2 Changeset Naming

Changeset IDs should be globally unique and descriptive.

Recommended format:

```text
v<version>-<sequence>-<description>
```

Examples:

```text
v1.0-001-create-subscription
v1.0-002-create-notification-event
v1.0-003-create-delivery-attempt
```

A changeset that has already been executed in any environment must **never be modified**.

If the schema needs to change, create a new changeset.

For example:

```text
v1.0-001-create-subscription
```

must not later be modified to add a column.

Instead:

```text
v1.1-001-add-subscription-description
```

should be created.

---


# 5. Enum-Like Columns

Columns representing a finite set of domain values must be mapped to Java enums.

The database should store the enum as a string rather than an ordinal number.

Example:

```java
@Enumerated(EnumType.STRING)
private SubscriptionStatus status;
```

Never use:

```java
@Enumerated(EnumType.ORDINAL)
```

because changing the Java enum order could corrupt the meaning of persisted values.

The database values must exactly match the Java enum names or the explicitly defined persistence representation.

---

# 6. `subscription`

The `subscription` table represents a customer's subscription to a particular notification event type. The only delivery channel is `WEBHOOK`, so the channel is not modeled as a column.

Suggested structure:

```text
subscription
--------------
id
client_id
event_type
status
webhook_url
created_at
updated_at
version
```

## 6.1 Columns

### `id`

Primary key.

```text
Type: BIGINT
Nullable: NO
Primary Key: YES
```

---

### `client_id`

The logical identifier of the client this subscription belongs to.

```text
Type: VARCHAR
Nullable: NO
```

---

### `event_type`

The business event that the client wants to receive notifications for.

```text
Type: VARCHAR
Nullable: NO
```

Possible values currently defined by the domain:

```text
credit_card_payment
cash_withdrawal
credit_transfer
debit_card_withdrawal
debit_automatic_payment
credit_refund
debit_transfer
credit_deposit
debit_purchase
credit_cashback
debit_subscription
```

The corresponding Java enum should represent these values.

Example:

```java
public enum EventType {
    CREDIT_CARD_PAYMENT,
    CASH_WITHDRAWAL,
    CREDIT_TRANSFER,
    DEBIT_CARD_WITHDRAWAL,
    DEBIT_AUTOMATIC_PAYMENT,
    CREDIT_REFUND,
    DEBIT_TRANSFER,
    CREDIT_DEPOSIT,
    DEBIT_PURCHASE,
    CREDIT_CASHBACK,
    DEBIT_SUBSCRIPTION
}
```

**Liquibase/SQL comment:**

```sql
COMMENT ON COLUMN subscription.event_type IS
'The business event type the client wants to be notified about. Values correspond to the EventType Java enum.';
```

---

### `status`

The lifecycle state of the subscription.

```text
Type: VARCHAR
Nullable: NO
```

Possible values:

```text
ACTIVE
INACTIVE
```

Corresponding Java enum:

```java
public enum SubscriptionStatus {
    ACTIVE,
    INACTIVE
}
```

**Liquibase/SQL comment:**

```sql
COMMENT ON COLUMN subscription.status IS
'Possible values: ACTIVE, INACTIVE. Must correspond to the SubscriptionStatus Java enum.';
```

---

### `webhook_url`

The webhook URL the notification payload is POSTed to.

There is intentionally **no `channel` column**: the only supported delivery channel is `WEBHOOK`, so the channel is implicit in this column and need not be modeled. A new channel must be introduced as an explicit domain/persistence change (adding a channel discriminator) rather than by reusing this column.

```text
Type: TEXT
Nullable: NO
```

---

### `created_at`

Timestamp when the subscription was created.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: NO
```

---

### `updated_at`

Timestamp when the subscription was last modified.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: NO
```

---

### `version`

Optimistic locking version.

```text
Type: BIGINT
Nullable: NO
Default: 0
```

The corresponding JPA entity **must** use:

```java
@Version
private Long version;
```

The application must not manually increment this field.

Hibernate is responsible for managing the optimistic-lock version.

---

# 7. `notification_event`

The `notification_event` table represents business events that must be processed by the notification service.

Suggested structure:

```text
notification_event
-------------------
id
event_id
event_type
event_version
correlation_id
status
payload
subscription_id
next_attempt_at
created_at
updated_at
version
```

## 7.1 Columns

### `id`

Primary key.

```text
Type: BIGINT
Nullable: NO
Primary Key: YES
```

---

### `event_id`

The canonical identity of the source event received from the broker.

The design uses `event_id == notification_event_id` semantics: the source event identity is preserved as the notification event identity and is used to enforce **idempotent ingestion** (repeated broker delivery of the same `event_id` must not create a second row).

Enforced by a database unique constraint (the durable source of truth for event identity):

```text
UNIQUE(event_id)
```

```text
Type: VARCHAR
Nullable: NO
```

---

### `event_type`

The type of business event.

```text
Type: VARCHAR
Nullable: NO
```

Possible values:

```text
credit_card_payment
cash_withdrawal
credit_transfer
debit_card_withdrawal
debit_automatic_payment
credit_refund
debit_transfer
credit_deposit
debit_purchase
credit_cashback
debit_subscription
```

Corresponding Java enum:

```java
public enum EventType {
    CREDIT_CARD_PAYMENT,
    CASH_WITHDRAWAL,
    CREDIT_TRANSFER,
    DEBIT_CARD_WITHDRAWAL,
    DEBIT_AUTOMATIC_PAYMENT,
    CREDIT_REFUND,
    DEBIT_TRANSFER,
    CREDIT_DEPOSIT,
    DEBIT_PURCHASE,
    CREDIT_CASHBACK,
    DEBIT_SUBSCRIPTION
}
```

**Liquibase/SQL comment:**

```sql
COMMENT ON COLUMN notification_event.event_type IS
'The business event type. Values correspond to the EventType Java enum.';
```

---

### `event_version`

The version of the event contract/schema.

This is **not** the same thing as the database optimistic-lock `version`.

```text
Type: INTEGER
Nullable: NO
Default: 1
```

---

### `correlation_id`

Correlation identifier propagated from the source event for logging and tracing.

```text
Type: VARCHAR
Nullable: YES
```

---

### `status`

The processing lifecycle of the notification event.

```text
Type: VARCHAR
Nullable: NO
```

Possible values:

```text
PENDING
DELIVERING
RETRY_SCHEDULED
COMPLETED
FAILED
```

Corresponding Java enum:

```java
public enum NotificationEventStatus {
    PENDING,
    DELIVERING,
    RETRY_SCHEDULED,
    COMPLETED,
    FAILED
}
```

**Liquibase/SQL comment:**

```sql
COMMENT ON COLUMN notification_event.status IS
'Possible values: PENDING, DELIVERING, RETRY_SCHEDULED, COMPLETED, FAILED. Must correspond to the NotificationEventStatus Java enum.';
```

---

### `payload`

The event payload.

```text
Type: JSONB
Nullable: NO
```

The payload should contain the business-event data required to process the notification.

The application should validate the payload before processing it.

---

### `subscription_id`

The subscription matched for `(client_id, event_type)` at ingestion time.

`NULL` when no active subscription matched; in that case the notification event is persisted directly as `FAILED` (no delivery attempts are created for it).

```text
Type: BIGINT
Nullable: YES
Foreign Key: subscription(id)
```

Recommended foreign key:

```text
notification_event.subscription_id
    → subscription.id
```

---

### `next_attempt_at`

Earliest time the next delivery attempt may run while the notification event is `RETRY_SCHEDULED`.

Backs the database-backed retry schedule so that retry timing survives application restarts. `NULL` while no retry is scheduled.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: YES
```

---

### `created_at`

Timestamp when the event was created/received.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: NO
```

---

### `updated_at`

Timestamp when the event was last modified.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: NO
```

---

### `version`

Optimistic locking version.

```text
Type: BIGINT
Nullable: NO
Default: 0
```

The JPA entity **must** use:

```java
@Version
private Long version;
```

---

# 8. `delivery_attempt`

The `delivery_attempt` table represents individual attempts to deliver a notification.

If delivery fails and the notification is retried, a new `delivery_attempt` record is created.

There is intentionally no separate `deliveries` table in this design.

Suggested structure:

```text
delivery_attempt
-----------------
id
notification_event_id
status
attempt_number
error_message
created_at
completed_at
version
```

---

## 8.1 Columns

### `id`

Primary key.

```text
Type: BIGINT
Nullable: NO
Primary Key: YES
```

---

### `notification_event_id`

Reference to the notification event being delivered.

```text
Type: BIGINT
Nullable: NO
Foreign Key: notification_event(id)
```

Recommended foreign key:

```text
delivery_attempt.notification_event_id
    → notification_event.id
```

---

### `status`

The lifecycle state of the delivery attempt.

```text
Type: VARCHAR
Nullable: NO
```

Possible values:

```text
IN_PROGRESS
SUCCESS
FAILED
```

Corresponding Java enum:

```java
public enum DeliveryAttemptStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILED
}
```

**Liquibase/SQL comment:**

```sql
COMMENT ON COLUMN delivery_attempt.status IS
'Possible values: IN_PROGRESS, SUCCESS, FAILED. Must correspond to the DeliveryAttemptStatus Java enum.';
```

---

### `attempt_number`

Sequential number of the delivery attempt for a notification event.

Example:

```text
1
2
3
```

where:

* `1` = first attempt
* `2` = first retry
* `3` = second retry

```text
Type: INTEGER
Nullable: NO
```

Recommended constraint:

```text
attempt_number > 0
```

---

### `error_message`

Error information when a delivery attempt fails.

```text
Type: VARCHAR/TEXT
Nullable: YES
```

This should normally be populated when:

```text
status = FAILED
```

It may be `NULL` for successful attempts.

Do not store sensitive information such as credentials, authorization headers, tokens, or secrets.

---

### `created_at`

Timestamp when the delivery attempt started.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: NO
```

---

### `completed_at`

Timestamp when the delivery attempt finished.

```text
Type: TIMESTAMP WITH TIME ZONE
Nullable: YES
```

This should normally be populated when the attempt reaches a terminal state:

```text
SUCCESS
FAILED
```

---

### `version`

Optimistic locking version.

```text
Type: BIGINT
Nullable: NO
Default: 0
```

The corresponding JPA entity **must** use:

```java
@Version
private Long version;
```

---

# 9. Indexes

Indexes should be created based on actual query patterns.

At minimum, consider indexes for:

### `subscription`

```text
(event_type, status)
```

This supports finding active subscriptions for a particular event type.

The domain requires a single subscription per `(client_id, event_type)` combination, so the table enforces:

```text
unique(client_id, event_type)
```

---

### `notification_event`

Consider:

```text
(status, created_at)
```

This supports processing pending/retryable events.

For due-retry claiming, also consider:

```text
(status, next_attempt_at)
```

---

### `delivery_attempt`

Consider:

```text
(notification_event_id)
```

and potentially:

```text
(notification_event_id, attempt_number)
```

depending on the query patterns.

---

# 10. JPA Persistence Entities

JPA entities must belong to the persistence adapter rather than the domain model.

Recommended package:

```text
com.fardorado.notification.adapter.out.persistence.entity
```

Example:

```text
adapter/
└── out/
    └── persistence/
        ├── entity/
        │   ├── SubscriptionEntity.java
        │   ├── NotificationEventEntity.java
        │   └── DeliveryAttemptEntity.java
        └── repository/
            ├── SubscriptionJpaRepository.java
            ├── NotificationEventJpaRepository.java
            └── DeliveryAttemptJpaRepository.java
```

Do not use JPA annotations directly on pure domain entities unless explicitly required by the architecture.

---

# 12. Optimistic Locking

Every table containing a `version` column must have a corresponding JPA entity using Hibernate optimistic locking.

Example:

```java
@Entity
@Table(name = "subscription")
public class SubscriptionEntity {

    @Id
    private Long id;

    @Version
    private Long version;

    // ...
}
```

The following entities therefore require optimistic locking:

```text
SubscriptionEntity
NotificationEventEntity
DeliveryAttemptEntity
```

The application must not manually update the `version` field.

Hibernate handles version increments automatically.

---

# 13. Enum Persistence

Java enums must be persisted using their string representation.

Correct:

```java
@Enumerated(EnumType.STRING)
@Column(name = "status", nullable = false)
private SubscriptionStatus status;
```

Incorrect:

```java
@Enumerated(EnumType.ORDINAL)
private SubscriptionStatus status;
```

Using ordinal persistence is prohibited.

When adding a new enum value:

1. Update the Java enum.
2. Update the database documentation/comments.
3. Add or update tests.
4. If database constraints explicitly enumerate valid values, create a new Liquibase changeset.
5. Never modify an already-executed Liquibase changeset.

---

# 14. Database Constraints

Business invariants that can safely be enforced at the database level should be represented using database constraints.

Examples:

```text
PRIMARY KEY
FOREIGN KEY
NOT NULL
UNIQUE
CHECK
```

For example:

```sql
CHECK (attempt_number > 0)
```

However, complex business rules should remain in the domain/application layer rather than being encoded exclusively in SQL.

---

# 15. Foreign Keys

Relationships between tables must be explicitly represented using foreign keys.

Required relationships:

```text
notification_event.subscription_id
        ↓
subscription.id
```

```text
delivery_attempt.notification_event_id
        ↓
notification_event.id
```

Foreign keys must have explicit names.

Examples:

```text
fk_notification_event_subscription
fk_delivery_attempt_notification_event
```

---

# 16. JPA Relationships

JPA relationships must be configured deliberately.

Avoid blindly using:

```java
CascadeType.ALL
```

unless the aggregate lifecycle explicitly requires cascading.

Avoid unnecessary bidirectional relationships.

Prefer unidirectional relationships when they are sufficient for the use case.

Collections should generally use lazy loading.

Example:

```java
@OneToMany(fetch = FetchType.LAZY)
```

Persistence relationships must not leak into domain entities.

---

# 17. Transaction Boundaries

Transactions belong to the application/use-case layer.

Typical pattern:

```java
@Transactional
public void processNotification(...) {
    // use case
}
```

The domain model must not depend on Spring's transaction APIs.

The persistence adapter participates in the transaction established by the application layer.

---

# 18. Database Migrations and Application Changes

Database changes and application changes must be backward compatible whenever possible.

For example, when adding a new column:

### Step 1

Add the nullable column through Liquibase.

### Step 2

Deploy application code that understands the column.

### Step 3

Populate existing records if required.

### Step 4

Only then make the column `NOT NULL`, if appropriate.

Avoid migrations that require the application and database to change simultaneously in an incompatible way.

---

# 19. Testing

Database integration tests should use a real PostgreSQL instance.

Recommended technology:

```text
Testcontainers + PostgreSQL
```

Tests should verify:

* Liquibase migrations execute successfully.
* Tables are created correctly.
* Constraints are enforced.
* Foreign keys work.
* Indexes are created where required.
* Enum values are persisted correctly.
* Optimistic locking works.
* JPA mappings match the database schema.

Example test configuration:

```text
Testcontainers PostgreSQL
        ↓
Liquibase migrations
        ↓
Spring Data JPA
        ↓
Persistence integration tests
```

Do not rely exclusively on H2 for persistence tests because H2 is not PostgreSQL.

---

# 20. Jpa Repositories
Implement all Jpa Repositories for all Jpa Entities.

