# Architecture Conventions — notification-service

## 1. Purpose

This document defines the architectural conventions and implementation rules for the `notification-service`.

These conventions are **mandatory** for all new code and modifications to existing code.

The service must follow:

* Hexagonal Architecture (Ports and Adapters)
* Domain-Driven Design (DDD) principles where applicable
* SOLID principles
* Dependency Inversion Principle
* Separation of concerns
* Explicit boundaries between domain, application, and infrastructure
* Testability through dependency inversion

The purpose of this document is to prevent architectural drift and ensure that implementations remain consistent as the service evolves.

> **AI coding-agent rule:** Before creating or modifying code, inspect this document and ensure the proposed implementation complies with all applicable rules.

---

# 2. Architectural Style

The service uses **Hexagonal Architecture (Ports and Adapters)**.

The architecture is divided into three primary areas:

```text
                 ┌─────────────────┐
                 │     ADAPTERS    │
                 │                 │
                 │ REST / Kafka    │
                 │ DB / Email      │
                 └────────┬────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │   APPLICATION   │
                 │                 │
                 │ Input Ports     │
                 │ Use Cases       │
                 │ Output Ports    │
                 └────────┬────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │     DOMAIN      │
                 │                 │
                 │ Entities        │
                 │ Value Objects   │
                 │ Domain Services │
                 │ Domain Rules    │
                 └─────────────────┘
```

The **domain and application layers form the application core. Infrastructure and adapters depend on this core; the core must not depend on infrastructure.**. Also:

- Domain MUST NOT depend on Application.
- Application MAY depend on Domain.
- Application ports are owned by Application.
- Domain abstractions are owned by Domain.
- Adapters depend on Application ports.
- Adapters MUST NOT be dependencies of Domain or Application.
- Infrastructure technologies must never dictate domain design.

---

# 3. Dependency Rule

The most important architectural rule is:

> **Dependencies must always point toward the domain.**

Allowed dependency direction:

```text
Infrastructure → Application → Domain
Adapters       → Ports       → Domain
```

The following dependency is forbidden:

```text
Domain → Application
Domain → Infrastructure
Application → Infrastructure
Domain → Spring
Domain → JPA
Domain → Kafka
Domain → PostgreSQL
```

The domain must remain independent of frameworks, databases, messaging systems, HTTP, and external providers.

---

# 4. Recommended Package Structure

The project should use a package structure similar to:

```text
com.fardorado.notification
│
├── domain
│   ├── model
│   │   ├── notification
│   │   ├── delivery
│   │   └── ...
│   │
│   ├── service
│   │
│   ├── exception
│   │
│   └── ...
│
├── application
│   ├── port
│   │   ├── in
│   │   └── out
│   │
│   ├── service
│   │
│   ├── command
│   │
│   └── ...
│
├── adapter
│   ├── in
│   │   ├── web
│   │   │   └── impl
│   │   ├── messaging
│   │   └── scheduler
│   │
│   └── out
│       ├── persistence
│       ├── messaging
│       ├── email
│       ├── sms
│       └── ...
│
└── configuration
```

The exact package names may evolve, but the architectural boundaries must remain.

---

# 5. Domain Layer

The domain layer contains business concepts and business rules.

Examples:

* `Notification`
* `NotificationId`
* `NotificationStatus`
* `NotificationChannel`
* `Delivery`
* `DeliveryStatus`
* domain services
* domain exceptions
* business invariants

## 5.1 Domain Independence

The domain layer MUST NOT depend on:

* Spring Framework
* Spring Boot
* Spring Data
* JPA/Hibernate
* Kafka
* RabbitMQ
* PostgreSQL
* Redis
* HTTP
* REST
* Jackson
* external APIs
* infrastructure configuration

For example, this is forbidden:

```java
@Entity
public class Notification {
}
```

The domain model must not be a persistence model.

Similarly, this is forbidden:

```java
@Service
public class NotificationDomainService {
}
```

Spring annotations belong outside the domain.

---

# 6. Domain Entities

Entities must represent business concepts and protect their invariants.

Example:

```java
public final class Notification {

    private final NotificationId id;
    private NotificationStatus status;

    // Business behavior
}
```

Prefer behavior-oriented methods:

```java
notification.markAsProcessing();
notification.markAsSent();
notification.markAsFailed();
```

over exposing mutable state:

```java
notification.setStatus(...);
```

Entities should not become passive data containers.

---

# 7. Value Objects

Concepts that have identity through their value should be represented as value objects.

Examples:

```text
NotificationId
DeliveryId
CorrelationId
IdempotencyKey
Recipient
EmailAddress
PhoneNumber
NotificationTemplateId
```

Prefer:

```java
public record NotificationId(UUID value) {
}
```

over passing primitive values throughout the application:

```java
UUID notificationId
```

when the value has domain meaning.

Primitive obsession should be avoided when a concept has meaningful domain behavior or semantics.

---

# 8. Domain Services

A domain service may be introduced when business logic:

* does not naturally belong to a single entity/value object
* represents an important domain operation
* requires coordination between multiple domain concepts

Domain services must contain **business logic**, not infrastructure orchestration.

Incorrect:

```java
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
}
```

Correct conceptual separation:

```text
Application Service
       │
       ├── Load domain objects
       ├── Invoke domain behavior
       └── Persist domain objects

Domain Service
       │
       └── Business rules
```

---

# 9. Application Layer

The application layer orchestrates use cases.

It should answer:

> "What does the system do?"

Examples:

```text
CreateNotification
ProcessNotification
RetryNotification
GetNotificationStatus
CancelNotification
DispatchNotification
```

Application services coordinate the execution of use cases but should not contain infrastructure implementation details.

Example:

```java
public class SendNotificationUseCaseImpl
        implements SendNotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationDispatcher notificationDispatcher;

    // orchestration
}
```

The application layer may depend on domain objects and application ports.

---

# 10. Input Ports

Input ports represent operations that the application exposes to the outside world.

Example:

```java
public interface SendNotificationUseCase {

    NotificationResult send(SendNotificationCommand command);
}
```

Input ports belong to:

```text
application.port.in
```

Driving adapters invoke input ports.

For example:

```text
REST Controller
      │
      ▼
SendNotificationUseCase
      │
      ▼
Application Service
```

The REST controller must not call infrastructure components directly.

---

# 11. Output Ports

Output ports define dependencies that the application requires from external systems.

Examples:

```java
public interface NotificationRepository {
    Notification save(Notification notification);
    Optional<Notification> findById(NotificationId id);
}
```

```java
public interface NotificationChannel {
    DeliveryResult send(Notification notification);
}
```

```java
public interface EventPublisher {
    void publish(NotificationEvent event);
}
```

These interfaces belong to:

```text
application.port.out
```

Infrastructure implements them.

Example:

```text
Application
     │
     ▼
NotificationRepository
     ▲
     │
NotificationRepositoryImpl
```

The application must depend on the interface, never on the adapter.

---

# 12. Driving Adapters

Driving adapters initiate application behavior.

Examples:

* REST controllers
* Kafka consumers
* RabbitMQ consumers
* scheduled jobs
* CLI adapters

Their responsibilities are limited to:

1. Receive external input.
2. Validate transport-level concerns.
3. Map external input into application commands.
4. Invoke an input port.
5. Map application results to the external representation.

They must NOT contain business logic.

Example:

```java
@RestController
class NotificationController {

    private final SendNotificationUseCase useCase;

    @PostMapping("/notifications")
    ResponseEntity<?> send(@RequestBody SendNotificationRequestDto request) {

        var command = request.toCommand();

        var result = useCase.send(command);

        return ResponseEntity.accepted().body(result);
    }
}
```

The controller must not:

* access repositories
* publish Kafka messages directly
* call email providers
* implement retry logic
* modify domain state directly
* contain business rules

---

# 13. Driven Adapters

Driven adapters implement application output ports.

Examples:

```text
PostgreSQL adapter
Kafka adapter
Email provider adapter
SMS provider adapter
Push notification adapter
Redis adapter
```

Infrastructure details belong here.

Example:

```java
@Component
class SmtpEmailAdapter implements NotificationChannel {

    @Override
    public DeliveryResult send(Notification notification) {
        // SMTP implementation
    }
}
```

The application must remain unaware that SMTP is being used.

---

# 14. Persistence Rules

Persistence technology must remain inside the infrastructure layer.

JPA entities must NOT be the domain entities.

Use separate persistence models when necessary:

```text
Domain:

Notification

Persistence:

NotificationEntity
```

Example:

```java
@Entity
@Table(name = "notifications")
class NotificationEntity {
}
```

Mapping should occur in the persistence adapter.

```text
NotificationEntity
        ↕
NotificationMapper
        ↕
Notification
```

Do not leak JPA annotations or persistence concerns into the domain.

---

# 15. Repository Rules

Repositories are application output ports.

Example:

```java
public interface NotificationRepository {

    Optional<Notification> findById(NotificationId id);

    Notification save(Notification notification);
}
```

The JPA implementation belongs in infrastructure:

```java
@Component
class NotificationRepositoryImpl
        implements NotificationRepository {
}
```

Spring Data repositories must not be injected directly into application services.

Forbidden:

```java
class SendNotificationUseCaseImpl {

    private final NotificationJpaRepository repository;
}
```

Preferred:

```java
class SendNotificationUseCaseImpl {

    private final NotificationRepository repository;
}
```

---

# 16. Messaging Rules

Messaging infrastructure must not leak into the domain.

The domain must not know about:

* Kafka
* Kafka topics
* partitions
* consumer groups
* offsets
* Kafka headers
* Kafka `ConsumerRecord`
* serialization formats

For example, this is forbidden:

```java
public class Notification {

    public void publish(KafkaTemplate<?, ?> kafkaTemplate) {
    }
}
```

Instead:

```text
Kafka Consumer
      │
      ▼
Input Port
      │
      ▼
Application Service
      │
      ▼
Output Port
      │
      ▼
Kafka Adapter
```

---

# 17. Event-Driven Architecture

The service may use asynchronous messaging for notification processing.

External events should be translated into application commands.

Example:

```text
Kafka Event
    │
    ▼
Kafka Consumer Adapter
    │
    ▼
ProcessNotificationUseCase
    │
    ▼
Application Service
```

The Kafka event schema should not become the domain model automatically.

Use explicit mapping:

```text
KafkaNotificationEvent
        ↓
ProcessNotificationCommand
        ↓
Domain Model
```

---

# 18. Notification Dispatching

Notification delivery should be abstracted behind an application output port.

Example:

```java
public interface NotificationDispatcher {

    DeliveryResult dispatch(Notification notification);
}
```

The dispatcher may select the appropriate channel adapter:

```text
NotificationDispatcher
        │
        ├── Email
        ├── SMS
        └── Push
```

Channel-specific technologies must remain outside the domain.

For example:

```text
EmailChannelAdapter
TwilioSmsAdapter
FirebasePushAdapter
```

should all implement application-defined abstractions.

---

# 19. Retry Architecture

Retries are application/infrastructure concerns, not domain infrastructure concerns.

The notification domain may represent concepts such as:

```text
DeliveryStatus
attemptCount
nextAttemptAt
lastError
```

However, the scheduling and execution mechanism belongs to the application/infrastructure layers.

A conceptual retry flow:

```text
Delivery fails
      │
      ▼
Persist retry information
      │
      ▼
Retry Dispatcher
      │
      ▼
Delivery Worker Pool
      │
      ▼
Notification Dispatcher
      │
      ▼
External Provider
```

The retry mechanism must not be embedded in REST controllers or domain entities.

---

# 20. Delivery Worker Pool

Workers are infrastructure/application orchestration components.

They may use:

* Spring scheduling
* `ExecutorService`
* Spring `TaskExecutor`
* virtual threads
* message consumers
* database polling

The selected technology must remain replaceable.

The worker must invoke an application use case rather than directly manipulating repositories or domain state.

Preferred:

```text
Worker
  ↓
ProcessDeliveryUseCase
  ↓
Application Service
  ↓
Domain
```

Forbidden:

```text
Worker
  ↓
JpaRepository
  ↓
Database
```

---

# 21. Transaction Boundaries

Transaction boundaries belong to the application/infrastructure boundary.

They should normally surround an application use case rather than individual domain methods.

Example:

```java
@Transactional
public void processDelivery(...) {
    // application orchestration
}
```

The domain must not contain:

```java
@Transactional
```

or any transaction-management technology.

---

# 22. Idempotency

Operations that may be retried or delivered more than once must be idempotent where required.

The service should explicitly distinguish:

```text
Correlation ID
Idempotency Key
Notification ID
Delivery ID
Event ID
```

These concepts must not automatically be treated as interchangeable.

If idempotency is required, it should be enforced at the appropriate application/persistence boundary.

Database uniqueness constraints should be used where appropriate as the final consistency boundary.

---

# 23. Correlation ID

Correlation IDs are infrastructure/application concerns.

They may be propagated through:

```text
HTTP headers
Kafka headers
logs
application commands
events
```

The domain should only receive a correlation ID if it has actual business meaning.

Do not introduce correlation IDs into every domain object merely for logging purposes.

---

# 24. Exception Handling

Domain exceptions represent business rule violations.

Examples:

```java
NotificationAlreadySentException
InvalidNotificationStatusTransitionException
UnsupportedNotificationChannelException
```

Infrastructure exceptions must remain infrastructure-specific.

For example:

```text
DataAccessException
KafkaException
HttpClientException
SMTPException
```

must not leak into the domain.

Adapters should translate infrastructure failures into application-level results/exceptions where appropriate.

---

# 25. Validation

Validation must occur at the appropriate boundary.

### Transport validation

REST DTO validation may use:

```java
@NotNull
@NotBlank
@Email
@Size
```

This protects the API boundary.

### Domain validation

Business invariants must be enforced by the domain itself.

Example:

```java
notification.markAsSent();
```

must reject invalid state transitions regardless of whether the operation originated from:

* REST
* Kafka
* scheduler
* tests
* another adapter

Never rely exclusively on controller validation for business invariants.

---

# 26. DTO Rules

External DTOs must not become domain models.

REST:

```text
SendNotificationRequestDto
        ↓
SendNotificationCommand
        ↓
Domain
```

Database:

```text
NotificationEntity
        ↕
Notification
```

Kafka:

```text
NotificationKafkaMessage
        ↓
Application Command
```

Avoid passing:

```java
Map<String, Object>
```

through the entire application.

Use explicit types.

---

# 27. Mapping Rules

Mappings between architectural layers must be explicit.

Examples:

```text
REST DTO → Application Command
Kafka Message → Application Command
Domain → Response DTO
JPA Entity ↔ Domain Entity
Domain Event → Integration Event
```

Do not expose persistence entities directly through REST APIs.

Do not expose domain entities directly as API contracts.

---

# 28. Configuration Rules

Configuration belongs outside the domain.

Examples:

* Spring configuration
* database configuration
* Kafka configuration
* HTTP clients
* thread pools
* retry policies
* provider credentials
* timeouts

These belong in configuration/infrastructure packages.

Prefer typed configuration:

```java
@ConfigurationProperties
```

over scattering:

```java
@Value("${...}")
```

throughout application services.

---

# 29. External Services

External services must always be accessed through ports.

Forbidden:

```java
class SendNotificationUseCaseImpl {

    private final RestClient restClient;
}
```

Preferred:

```java
class SendNotificationUseCaseImpl {

    private final NotificationProvider notificationProvider;
}
```

with:

```java
interface NotificationProvider {
    DeliveryResult send(...);
}
```

and:

```text
NotificationProvider
        ▲
        │
ExternalNotificationProviderAdapter
```

This allows the provider to be replaced without modifying application/domain logic.

---

# 30. Dependency Injection

Use constructor injection exclusively.

Preferred:

```java
@Component
class ProcessNotificationUseCaseImpl {

    private final NotificationRepository repository;

    ProcessNotificationUseCaseImpl(
            NotificationRepository repository) {
        this.repository = repository;
    }
}
```

Avoid field injection:

```java
@Autowired
private NotificationRepository repository;
```

Do not use dependency injection as a substitute for good architecture.

---

# 31. Spring Usage

Spring Boot is an infrastructure/framework concern.

Spring annotations are allowed in:

```text
adapter
configuration
infrastructure
application
```

but should generally be avoided in:

```text
domain
```

The domain should ideally be executable in a plain Java environment without starting Spring.

---

# 32. Testing Architecture

The architecture must make the domain and application layers easy to test without infrastructure.

## Domain tests

Domain tests should not require:

* Spring context
* database
* Kafka
* Docker
* external APIs

Example:

```java
@Test
void shouldNotAllowSendingAlreadySentNotification() {
    ...
}
```

## Application tests

Application services should use mocked/fake ports:

```text
Application Service
      │
      ├── Fake NotificationRepository
      └── Fake NotificationDispatcher
```

## Adapter tests

Infrastructure adapters may use integration tests.

Examples:

* PostgreSQL/Testcontainers
* Kafka/Testcontainers
* WireMock
* provider sandbox

---

# 33. Architecture Tests

The project should preferably include automated architecture tests using a tool such as **ArchUnit**.

Examples of rules:

```text
domain must not depend on adapter
domain must not depend on Spring
domain must not depend on JPA
application must not depend on adapter
controllers must depend only on input ports
```

Example conceptual rule:

```java
noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..adapter..", "org.springframework..");
```

Architecture rules should fail the build when violated.

---

# 34. Logging

Logging must occur primarily at application/infrastructure boundaries.

Do not put infrastructure logging code into domain entities.

Avoid logging:

* credentials
* authentication tokens
* provider secrets
* sensitive notification content
* personally identifiable information unless explicitly required

Use structured logging where practical.

Important identifiers may include:

```text
notificationId
deliveryId
correlationId
eventId
```

---

# 35. Observability

Observability concerns belong outside the domain.

The service should support, where appropriate:

```text
Logs
Metrics
Tracing
Health checks
```

Distributed tracing should propagate correlation/trace context through:

```text
HTTP
Messaging
Application boundaries
External calls
```

Do not pollute domain objects with tracing infrastructure.

---

# 36. API Rules

REST APIs should expose application capabilities, not internal implementation details.

Controllers should be thin.

Preferred:

```text
Controller
    ↓
Input Port
    ↓
Application Service
```

Avoid:

```text
Controller
    ↓
Repository
    ↓
Provider
```

HTTP-specific concerns such as:

* HTTP status codes
* headers
* request DTOs
* response DTOs

must remain in the REST adapter.

---

# 37. Database Rules

The database is an implementation detail.

The domain must not assume:

* PostgreSQL
* table names
* column names
* indexes
* SQL
* JPA
* Hibernate

Database constraints should be used to protect critical consistency rules.

Examples:

```text
UNIQUE(idempotency_key)
UNIQUE(notification_id, channel)
INDEX(status, next_attempt_at)
```

The exact schema is defined separately from this architectural convention document.

---

# 38. Utility Classes

Avoid creating generic utility classes.

Forbidden pattern:

```text
NotificationUtils
NotificationHelper
CommonUtils
ServiceHelper
```

Prefer domain-specific abstractions.

If a behavior has domain meaning, place it in:

* entity
* value object
* domain service

If it is application orchestration, place it in the application layer.

If it is infrastructure-specific, place it in the corresponding adapter.

---

# 39. Static State

Avoid mutable static state.

Do not use static variables as an alternative to dependency injection.

Global mutable state is forbidden unless there is an explicitly documented infrastructure requirement.

---

# 40. Threading and Concurrency

Concurrency is an infrastructure/application concern.

The domain model should remain thread-safe through proper encapsulation and immutable value objects where practical.

Worker pools must not directly mutate shared domain state.

Concurrency control should be implemented through appropriate mechanisms such as:

```text
Database locking
Optimistic locking
Atomic state transitions
Message partitioning
Idempotency
```

depending on the use case.

---

# 41. Time Handling

Avoid directly calling:

```java
Instant.now()
```

throughout application/domain logic.

Prefer injecting a clock abstraction where deterministic behavior is important:

```java
Clock clock;
```

This makes time-dependent business logic testable.

---

# 42. Randomness and UUID Generation

Avoid directly coupling domain logic to random generators when deterministic tests are important.

Where appropriate, abstract:

```text
ID generation
random values
external identifiers
```

behind application-level abstractions.

---

# 43. Dependency Restrictions

The following dependencies should be treated as infrastructure dependencies:

```text
Spring Boot
Spring Web
Spring Data
Spring Kafka
JPA
Hibernate
Jackson
PostgreSQL driver
Redis
HTTP clients
Kafka clients
provider SDKs
```

They must not leak into the domain model.

---

# 44. Forbidden Architectural Patterns

The following patterns are explicitly forbidden.

## 44.1 Anemic domain caused by infrastructure

Do not turn domain objects into simple database DTOs.

## 44.2 Controller-driven business logic

Business rules must not live in controllers.

## 44.3 Repository-driven application architecture

Do not design use cases around database operations.

Bad:

```text
Controller → Repository → Response
```

Good:

```text
Controller
    ↓
Use Case
    ↓
Domain
    ↓
Repository Port
```

## 44.4 Domain depending on frameworks

Forbidden:

```text
Domain → Spring
Domain → JPA
Domain → Kafka
```

## 44.5 Infrastructure leaking into application

Forbidden:

```java
KafkaTemplate
JpaRepository
RestClient
WebClient
```

directly injected into domain-oriented use cases when a port should exist.

## 44.6 Shared "common" package

Avoid creating a large:

```text
common
utils
shared
```

package containing unrelated functionality.

Shared abstractions should exist only when they represent a genuine architectural/domain concept.

---

# 45. Dependency Direction Summary

The following dependency direction must always be preserved:

```text
                    DOMAIN
                      ▲
                      │
                APPLICATION
                      ▲
                      │
              ADAPTER / INFRA
```

More precisely:

```text
Driving Adapter
      │
      ▼
 Input Port
      │
      ▼
Application Service
      │
      ├───────────────► Domain
      │
      ▼
 Output Port
      ▲
      │
Driven Adapter
```

The implementation of a port is always outside the layer that defines the port.

---

# 46. AI Coding Agent Rules

When an AI coding agent modifies this repository, it MUST follow these rules.

## Rule 1 — Inspect architecture first

Before implementing a feature, identify:

1. Domain concepts involved.
2. Application use case involved.
3. Input port required.
4. Output ports required.
5. Driving adapter required.
6. Driven adapter required.

Do not immediately create a controller, repository, or service without determining the appropriate architectural boundary.

---

## Rule 2 — Do not bypass ports

An adapter must never bypass the application layer.

Forbidden:

```text
REST Controller → Repository
REST Controller → Kafka
REST Controller → External API
```

Preferred:

```text
REST Controller
      ↓
Input Port
      ↓
Application Service
      ↓
Output Port
      ↓
Adapter
```

---

## Rule 3 — Do not introduce framework dependencies into domain

An AI agent must reject implementations that introduce:

```text
Spring annotations
JPA annotations
Kafka classes
HTTP classes
Jackson annotations
```

into domain classes unless this document is explicitly changed.

---

## Rule 4 — Prefer existing abstractions

Before creating a new:

```text
Service
Repository
Port
Adapter
Value Object
Exception
```

inspect the existing codebase.

Do not create duplicate abstractions.

---

## Rule 5 — Do not over-engineer

Do not introduce an abstraction simply because Hexagonal Architecture allows it.

Create a port when there is a meaningful architectural boundary or external dependency.

Avoid unnecessary interfaces such as:

```text
NotificationServiceInterface
NotificationManagerInterface
NotificationHelperInterface
```

when there is no architectural reason for them.

---

## Rule 6 — Business rules belong in the domain

If a rule represents a business invariant, it must be enforced by the domain and not only by:

* REST validation
* database constraints
* Kafka consumers
* scheduled jobs

Infrastructure constraints may complement domain rules but must not replace them.

---

## Rule 7 — Keep adapters thin

Adapters translate between external representations and internal application contracts.

They should not orchestrate business workflows.

---

## Rule 8 — Keep application services focused

An application service should orchestrate one or a small number of closely related use cases.

Do not create a massive:

```text
NotificationService
```

containing every operation in the system.

Prefer focused use cases.

---

## Rule 9 — Preserve testability

A new feature should be testable without requiring the complete infrastructure stack whenever possible.

Domain tests should remain pure Java tests.

Application tests should be executable using mocks/fakes of output ports.

---

## Rule 10 — Add architecture tests for important boundaries

When a new architectural rule is introduced, consider enforcing it with ArchUnit or equivalent automated tests.

Architecture should be executable, not merely documented.

---

# 47. Guiding Principle

When in doubt, prefer this question:

> **"If we replaced Spring Boot, PostgreSQL, Kafka, or the notification provider tomorrow, how much of the domain and application code would need to change?"**

The desired answer is:

> **Very little.**

The business logic should survive changes to infrastructure.

The architecture should therefore keep the following concepts independent:

```text
                    BUSINESS
                       │
                       ▼
                  DOMAIN MODEL
                       │
                       ▼
                  USE CASES
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
        INPUT PORTS         OUTPUT PORTS
             ▲                   ▲
             │                   │
      DRIVING ADAPTERS     DRIVEN ADAPTERS
             │                   │
             ▼                   ▼
          REST/KAFKA       DB/KAFKA/EMAIL/SMS
```

**The domain defines the rules.
The application defines the use cases.
Ports define boundaries.
Adapters implement technology.
Infrastructure is replaceable.**
