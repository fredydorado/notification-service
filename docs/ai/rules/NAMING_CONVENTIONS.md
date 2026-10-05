# Naming Conventions

Standardized naming helps in navigating the codebase efficiently.

## 1. Java & Spring Boot
* **Classes:** `PascalCase` (e.g., `UserRole`). 
* **Service interface:** use `Service` as suffix not as a full name of the class (e.g., `UserService`). For the implementation of a service interface, use `ServiceImpl` suffix. (e.g., `UserServiceImpl`).
* **REST DTOs:** use `RequestDto` / `ResponseDto` suffix (e.g., `SendNotificationRequestDto`, `NotificationResponseDto`).
* **Application commands:** use `Command` suffix (e.g., `SendNotificationCommand`).
* **Results:** use `Result` suffix (e.g., `NotificationResult`, `DeliveryResult`).
* **JPA Entities:** use `Entity` suffix (e.g., `UserEntity`). The `Jpa` infix is reserved for Spring Data repository interfaces (`*JpaRepository`, e.g., `UserJpaRepository`) and must not be used elsewhere (no `*JpaEntity`).
* **Methods/Variables:** `camelCase` (e.g., `calculateRefund`).
* **Constants:** `SCREAMING_SNAKE_CASE` (e.g., `MAX_RETRY_LIMIT`).
* **Exceptions:**  For all exceptions, use `Exception` suffix.
* **Packages:** `lowercase.dot.notation` (e.g., `com.fardorado.notification.domain.model`, `com.fardorado.notification.adapter`).
* **Interfaces:** Avoid prefixing with 'I'. Suffix ports appropriately (e.g., `LoadPolicyService`, `LoadPolicyRepository`).

## 2. Hexagonal Architecture Specifics
* **Domain:** 
  * **Domain services:** plain classes named after their domain responsibility (e.g., `NotificationDomainService`). Do not create interface + impl pairs for them (see `ARCHITECTURE_CONVENTIONS.md`, Rule 5 — no interfaces without an architectural reason).
* **Application:** Suffix by their technology:
  * **Use Cases (input ports):** For interfaces associated to use cases use `UseCase` suffix (e.g., `SendNotificationUseCase`).
  * **Implementation of Use Cases:** For classes that implement use case interfaces use `UseCaseImpl` suffix (e.g., `SendNotificationUseCaseImpl`).
  * **Outbound Ports:** Do not use `Port` suffix. Instead, use suffix ports appropriately:
    * Repositories: use `Repository` suffix (e.g., `UserRepository`).
    * Publishers: use `Publisher` suffix (e.g., `PaymentRequestMessagePublisher`).
  * **Mappers:** Use `Mapper` suffix.
* **Adapters:** Suffix by their technology:
  * **Repositories:** For the implementation of ports related to repositories, use `RepositoryImpl` suffix (e.g., `UserRepositoryImpl`).
  * **JPA Repositories:** use `JpaRepository` suffix (e.g., `UserJpaRepository`).
  * **Controllers:** 
    * Use `Controller` suffix for the interface where endpoints are defined and documented using OpenApi (e.g., `UserController`).
    * Use `ControllerImpl` suffix for the implementation of the controller interface (e.g., `UserControllerImpl`).
  * **Event Consumers:** use `Consumer` suffix (e.g., `KafkaNotificationConsumer`).
  * **Mappers:** Use `Mapper` suffix.

## 3. Database (PostgreSQL)
* **Tables:** `snake_case` and singular (e.g., `user`, `subscription`, `notification_event`).
* **Columns:** `snake_case` (e.g., `created_at`).
* **Constraints:** `pk_table_name`, `fk_source_target`, `idx_table_column`.

## 4. Testing 
* **Integration tests:** use `IntTest` suffix (e.g., `UserControllerIntTest`, `ClientControllerIntTest`).


## 5. Naming Conventions And Architectural Responsibility 

Use names that express architectural responsibility.

### Input ports

```text
SendNotificationUseCase
ProcessDeliveryUseCase
RetryNotificationUseCase
GetNotificationUseCase
```

### Output ports

```text
NotificationRepository
DeliveryRepository
NotificationDispatcher
EventPublisher
NotificationProvider
```

### Adapters

```text
NotificationController
KafkaNotificationConsumer
NotificationRepositoryImpl
KafkaEventPublisher
EmailNotificationAdapter
```

Avoid generic names such as:

```text
Utils
Helper
Manager
Processor
Handler
Service
```

unless the name accurately represents a specific architectural responsibility. `Service` alone as a full class name (e.g., `NotificationService`) is the pattern to avoid; a `Service` suffix on a class named after a specific responsibility (e.g., `UserService`) is acceptable.

---
