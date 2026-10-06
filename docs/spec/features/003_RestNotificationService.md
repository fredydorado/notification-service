# Notification Service — REST API Code Generation Instructions

## 1. Purpose

This document is an implementation specification for a coding AI agent working inside an existing **Java 21 / Spring Boot notification-service** project.

The goal is to generate the Java code required for the **REST API layer** of the notification service according to the existing design.

The generated implementation must integrate with the components that already exist in the project, especially the event-processing and retry mechanisms.

---

## 2. Scope

The coding agent is responsible for implementing the REST/API layer, including:

* REST controllers.
* Controller interfaces.
* Request DTOs.
* Response DTOs.
* Request validation.
* Controller-to-application/domain orchestration.
* Exception handling.
* Standardized HTTP error responses.
* OpenAPI documentation.
* API-specific mappers where necessary.
* Unit/integration tests for the REST layer.

The coding agent must **not reimplement event processing or retry logic**.

---

## 3. Existing Components Must Be Reused

The notification service already contains the implementation responsible for:

* Event consumption.
* Event persistence.
* Event processing.
* Subscription processing.
* Notification delivery.
* Retry scheduling.
* Retry dispatching.
* Delivery workers.
* Notification-event state management.
* Domain model.
* JPA entities.
* JPA repositories.
* Persistence services.
* Existing enums.

These components must be treated as existing functionality.

### Reuse-first rule

Before creating a new class, inspect the existing project.

If an existing component already provides the required behavior, reuse it rather than creating a duplicate implementation.

In particular, reuse existing:

* Domain entities.
* Domain services.
* Application services/use cases.
* Ports.
* JPA entities.
* JPA repositories.
* Repository adapters.
* Enums.
* Status definitions.
* Channel definitions.
* Exception types, if suitable.
* Mappers, if suitable.

Do not create duplicate representations of domain concepts simply for the REST layer unless an API-specific DTO is required.

---

# 4. REST Layer Architecture

Follow the architecture already established by the project.

The intended REST flow is:

```text
HTTP Client
    |
    v
Controller Interface
    |
    v
Controller Implementation
    |
    v
Application Use Case / Service
    |
    v
Existing Domain / Persistence Components
```

The controller must remain thin.

The controller should:

1. Receive the HTTP request.
2. Validate the request.
3. Convert API DTOs to the appropriate application/domain representation.
4. Invoke the existing application use case/service.
5. Convert the result to the appropriate response DTO.
6. Return the HTTP response.

The controller must not contain business logic.

---

# 5. Controller Interface

Define the REST API contract through a controller interface.

For example:

```java
public interface NotificationController {
    // endpoint method declarations
}
```

The controller implementation must implement this interface.

```java
@RestController
public class NotificationControllerImpl implements NotificationController {
    // implementation only
}
```

Use the project's existing package and naming conventions.

---

# 6. OpenAPI Documentation

OpenAPI/Swagger documentation must be placed on the **controller interface**, not on the controller implementation.

The interface should contain annotations such as:

```java
@Tag
@Operation
@ApiResponse
@ApiResponses
@Parameter
@RequestBody
```

as appropriate for the project's OpenAPI configuration.

Example structure:

```java
@Tag(name = "Notifications")
public interface NotificationController {

    @Operation(
        summary = "...",
        description = "..."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "..."),
        @ApiResponse(responseCode = "400", description = "..."),
        @ApiResponse(responseCode = "404", description = "..."),
        @ApiResponse(responseCode = "500", description = "...")
    })
    ResponseEntity<?> operation(...);
}
```

### Important

Do **not** duplicate OpenAPI annotations in the controller implementation.

The implementation class should focus on execution.

The exact OpenAPI annotations and metadata must follow the project's existing configuration and conventions.

Inspect the existing `pom.xml`/`build.gradle`, OpenAPI configuration, and existing controllers before adding dependencies or annotations.

Do not introduce a second OpenAPI library if one is already configured.

---

# 7. REST Endpoint Definitions

The coding agent **must implement exactly the following REST endpoints** as part of this challenge:

```text
GET  /notification_events
GET  /notification_events/{notification_event_id}
POST /notification_events/{notification_event_id}/replay
```

These endpoints are part of the REST/API layer only. The underlying event-processing, delivery, and retry components already exist and must be reused.

---

## 7.1 GET `/notification_events`

### Purpose

Retrieve notification events belonging to the authenticated client.

The endpoint provides a paginated list of notification events and supports filtering by date range and delivery status.

The authenticated principal determines the `client_id`.

### Important security rule

The client must **not** be able to provide an arbitrary `client_id` as a query parameter.

The authenticated principal/security context must determine which client's notification events can be accessed.

The application service must enforce client ownership when retrieving the events.

---

### Query Parameters

The endpoint supports:

| Parameter         | Required | Description                                 |
| ----------------- | -------- | ------------------------------------------- |
| `from`            | No       | Start of the event creation date/time range |
| `to`              | No       | End of the event creation date/time range   |
| `delivery_status` | No       | Filter by notification/delivery status      |
| `page`            | No*      | Page number                                 |
| `size`            | No*      | Number of records per page                  |

`page` and `size` should use the project's existing pagination conventions.

If the project does not define defaults, use reasonable API defaults and enforce a maximum page size through validation/configuration.

Example:

```http
GET /notification_events?from=2026-10-01T00:00:00Z&to=2026-10-05T23:59:59Z&delivery_status=FAILED&page=0&size=20
```

Do not expose a `client_id` query parameter.

---

### Validation

Validate:

* `from` has a valid date/time format.
* `to` has a valid date/time format.
* `from <= to`.
* `delivery_status` contains a supported status.
* `page >= 0`.
* `size > 0`.
* `size` does not exceed the configured maximum.

Invalid parameters must result in:

```text
400 BAD_REQUEST
```

using the standardized `ErrorResponseDto`.

---

### Response

Successful requests return:

```text
200 OK
```

The response must be paginated.

Use the project's existing pagination response convention if one already exists.

The logical response should contain:

```text
items
page
size
totalElements
totalPages
```

Each notification-event summary should expose the information defined by the API contract without exposing JPA/persistence implementation details.

At minimum, the list representation should provide enough information to identify and inspect the notification event, including:

```text
id
eventType
createdAt
status
attemptCount
lastHttpStatus
```

Do not expose the complete event payload unless the existing API design explicitly requires it.

---

### Example response

```json
{
  "items": [
    {
      "id": "notification-event-id",
      "eventType": "ORDER_CREATED",
      "createdAt": "2026-10-05T15:30:00Z",
      "status": "FAILED",
      "attemptCount": 3,
      "lastHttpStatus": 500
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

The exact DTO naming should follow the project's existing naming conventions.

---

### Application-layer interaction

The controller must delegate to an existing application service/use case responsible for querying notification events.

Conceptually:

```text
HTTP Request
    |
    v
NotificationController
    |
    v
NotificationEventQueryService / Use Case
    |
    v
Existing persistence components
```

The controller must not access the JPA repository directly.

The query must enforce the authenticated client's ownership.

---

## 7.2 GET `/notification_events/{notification_event_id}`

### Purpose

Retrieve the details of a specific notification event belonging to the authenticated client.

Example:

```http
GET /notification_events/123e4567-e89b-12d3-a456-426614174000
```

---

### Path Parameter

| Parameter               | Required | Description                          |
| ----------------------- | -------- | ------------------------------------ |
| `notification_event_id` | Yes      | Identifier of the notification event |

Validate the identifier according to its actual type in the existing domain model.

Do not create a second identifier representation merely for the REST layer.

---

### Ownership

The application service must verify that the requested notification event belongs to the authenticated client.

A client must not be able to retrieve another client's notification event by guessing or supplying its identifier.

If the resource does not exist or is not accessible to the authenticated client according to the project's security model, return:

```text
404 NOT_FOUND
```

using the standardized error response.

Do not leak information about whether another client's resource exists.

---

### Response

Successful requests return:

```text
200 OK
```

The detail response should contain:

```text
id
eventType
createdAt
status
attemptCount
lastHttpStatus
webhookUrl
attempts
```

The `attempts` collection represents the delivery-attempt history.

Each attempt should contain the information required by the API contract, including:

```text
attempt
status
httpStatus
```

Example:

```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "eventType": "ORDER_CREATED",
  "createdAt": "2026-10-05T15:30:00Z",
  "status": "FAILED",
  "attemptCount": 3,
  "lastHttpStatus": 500,
  "webhookUrl": "https://example.com/webhook",
  "attempts": [
    {
      "attempt": 1,
      "status": "FAILED",
      "httpStatus": 500
    },
    {
      "attempt": 2,
      "status": "FAILED",
      "httpStatus": 503
    },
    {
      "attempt": 3,
      "status": "FAILED",
      "httpStatus": 500
    }
  ]
}
```

Do not expose JPA entities directly.

Create/use an API response DTO.

---

### Errors

If the notification event does not exist or does not belong to the authenticated client:

```text
404 NOT_FOUND
```

If the identifier is syntactically invalid:

```text
400 BAD_REQUEST
```

Use the standardized `ErrorResponseDto`.

---

### Application-layer interaction

The controller must delegate the operation to an existing application/query service.

Conceptually:

```text
HTTP Request
    |
    v
NotificationController
    |
    v
GetNotificationEventUseCase
    |
    v
Existing Domain / Persistence Components
```

The controller must not:

* Query JPA repositories directly.
* Build the attempt history itself.
* Implement authorization/ownership logic directly.
* Reimplement notification-event processing.

---

## 7.3 POST `/notification_events/{notification_event_id}/replay`

### Purpose

Request replay/reprocessing of a notification event that has definitively failed.

Example:

```http
POST /notification_events/123e4567-e89b-12d3-a456-426614174000/replay
```

The replay operation is **asynchronous**.

The REST endpoint only requests the replay. The existing event-processing/retry infrastructure performs the actual processing.

---

### Path Parameter

| Parameter               | Required | Description                                    |
| ----------------------- | -------- | ---------------------------------------------- |
| `notification_event_id` | Yes      | Identifier of the notification event to replay |

Validate the identifier according to the existing domain model.

---

### Request Body

No request body is required.

The replay operation is completely identified by:

```text
notification_event_id
```

---

### Allowed State

Replay is allowed only for notification events that have **definitively failed**.

The design uses:

```text
FAILED
```

as the terminal failure state.

If the existing implementation distinguishes a permanent failure using a value such as:

```text
FAILED_PERMANENTLY
```

reuse the existing enum/domain representation rather than creating a new one.

Replay must **not** be allowed for:

```text
COMPLETED
```

or events that are currently being processed.

The normal state transition for replay is:

```text
FAILED
   |
   | replay
   v
PENDING
```

The existing event-processing infrastructure then continues processing the event.

Do not implement the actual retry/delivery processing inside the controller.

---

### Replay Semantics

The replay operation must be atomic with respect to the notification-event state.

Conceptually:

```text
1. Load notification event.
2. Verify client ownership.
3. Verify current state is FAILED.
4. Atomically transition:
       FAILED -> PENDING
5. Return 202 Accepted.
6. Existing event-processing infrastructure processes the event asynchronously.
```

The implementation must use the existing optimistic-locking/concurrency mechanisms.

Two concurrent replay requests must not cause duplicate replay processing.

For example:

```text
Request A: FAILED -> PENDING  SUCCESS
Request B: FAILED -> PENDING  REJECTED
```

The second request must not independently initiate another replay.

---

### Response

Successful replay request:

```text
202 ACCEPTED
```

The response should indicate that replay has been accepted for asynchronous processing.

Recommended response:

```json
{
  "notificationEventId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "PENDING"
}
```

If the existing API contract established `RETRY_SCHEDULED` as the externally returned replay status, preserve that contract. Do not introduce a new status solely for the REST layer.

The important semantic requirement is that the replay request is accepted asynchronously and does not wait for webhook delivery to complete.

---

### Replay Errors

#### Notification event does not exist

```text
404 NOT_FOUND
```

Return the standardized `ErrorResponseDto`.

---

#### Notification event does not belong to the authenticated client

```text
404 NOT_FOUND
```

Do not reveal another client's resource.

---

#### Notification event is not replayable

For example:

```text
COMPLETED
DELIVERING
PENDING
RETRY_SCHEDULED
```

Return:

```text
409 CONFLICT
```

using the standardized error response.

The error code should indicate that the resource is not in a replayable state, for example:

```text
NOTIFICATION_EVENT_NOT_REPLAYABLE
```

---

#### Concurrent replay

If another request has already transitioned the event from `FAILED` to `PENDING`, do not perform the transition again.

Handle the optimistic-lock/conditional-update result according to the project's concurrency conventions.

The endpoint must never create duplicate replay processing.

---

# 7.4 Endpoint Summary

The REST API exposed by the challenge is:

| Method | Endpoint                                              | Purpose                                                 | Success        |
| ------ | ----------------------------------------------------- | ------------------------------------------------------- | -------------- |
| `GET`  | `/notification_events`                                | List authenticated client's notification events         | `200 OK`       |
| `GET`  | `/notification_events/{notification_event_id}`        | Retrieve notification-event details and attempt history | `200 OK`       |
| `POST` | `/notification_events/{notification_event_id}/replay` | Request asynchronous replay of a failed event           | `202 ACCEPTED` |

Common error responses:

| HTTP Status | Meaning                                                                    |
| ----------- | -------------------------------------------------------------------------- |
| `400`       | Invalid request, path parameter, query parameter, or validation error      |
| `404`       | Notification event not found or not accessible to the authenticated client |
| `409`       | Resource state does not allow the requested operation                      |
| `500`       | Unexpected server-side error                                               |

All error responses must use the standardized `ErrorResponseDto`.

---

# 7.5 OpenAPI Requirements for These Endpoints

The controller interface must document all three endpoints.

Each endpoint should document:

### GET `/notification_events`

* Summary.
* Description.
* Query parameters.
* Successful `200` response.
* `400` validation response.
* `500` response where applicable.
* Response schema.

### GET `/notification_events/{notification_event_id}`

* Summary.
* Description.
* `notification_event_id` path parameter.
* Successful `200` response.
* `400` validation response.
* `404` response.
* `500` response where applicable.
* Response schema.

### POST `/notification_events/{notification_event_id}/replay`

* Summary.
* Description.
* `notification_event_id` path parameter.
* Successful `202` response.
* `400` validation response.
* `404` response.
* `409` non-replayable-state response.
* `500` response where applicable.
* Response schema.

All OpenAPI annotations must be placed on the **controller interface**, never duplicated on the controller implementation.

---

# 7.6 Implementation Constraints

The REST implementation must respect the following rules:

1. Do not implement Kafka/event consumption in the controllers.
2. Do not implement retry logic in the controllers.
3. Do not execute webhook calls from the controllers.
4. Do not access JPA repositories directly from the controllers.
5. Do not create a new persistence model.
6. Reuse the existing `NotificationEvent`, `DeliveryAttempt`, subscription, status, and channel domain concepts.
7. Reuse existing application services/use cases whenever available.
8. Reuse the existing optimistic-locking mechanism.
9. Enforce authenticated-client ownership.
10. Keep replay asynchronous.
11. Do not wait for notification delivery before returning `202 Accepted`.
12. Do not introduce a `deliveries` table/entity.
13. Validate all externally supplied parameters.
14. Return standardized `ErrorResponseDto` errors.
15. Keep OpenAPI documentation on the controller interface only.

---

# 8. Request DTOs

Create API request DTOs only when the REST contract requires them and an appropriate existing DTO does not already exist.

REST DTOs should not expose internal persistence entities directly.

For example, do not use:

```java
@PostMapping
public NotificationEventEntity create(
        @RequestBody NotificationEventEntity request) {
}
```

Instead, use an API-specific request DTO where appropriate:

```java
public record CreateSubscriptionRequest(
    ...
) {}
```

Use Java 21 features where appropriate, including records for immutable request/response DTOs when consistent with the project style.

---

# 9. Request Validation

All externally supplied REST input must be validated.

Use Jakarta Bean Validation:

```java
jakarta.validation.Valid
jakarta.validation.constraints.*
```

Typical annotations include:

```java
@NotNull
@NotBlank
@Size
@Pattern
@Positive
@PositiveOrZero
@Min
@Max
@Email
```

Use only the constraints appropriate for each field.

Example:

```java
public record CreateSubscriptionRequest(

    @NotBlank
    String eventType,

    @NotNull
    NotificationChannel channel

) {}
```

Controller methods should use:

```java
@Valid
```

where request-body validation is required.

For path/query parameters, use appropriate parameter-level validation.

If method-level validation is required, use the project's established Spring validation configuration.

---

# 10. Validation Requirements

Validation should occur at the API boundary.

Examples of invalid input that should be rejected before invoking business logic:

* Missing required fields.
* Blank strings.
* Invalid enum values.
* Invalid identifiers.
* Invalid pagination parameters.
* Negative values where prohibited.
* Values exceeding configured length limits.
* Invalid date/time formats.
* Invalid URL format where applicable.

Do not rely exclusively on database constraints for REST validation.

Do not duplicate business rules in DTO validation when those rules belong to the domain/application layer.

For example:

```text
@NotBlank
```

belongs to API validation.

A rule such as:

```text
"Subscription cannot be activated when..."
```

belongs to the application/domain layer.

---

# 11. Error Handling

Create a global REST exception handler using:

```java
@RestControllerAdvice
```

or:

```java
@ControllerAdvice
```

Use `@RestControllerAdvice` when the project follows the standard JSON REST response model.

Example:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    ...
}
```

Do not implement exception handling independently in every controller.

---

# 12. Custom Exceptions

Create custom exceptions for the relevant error types defined by the REST API.

The exact exception hierarchy should follow existing project conventions.

At minimum, distinguish between:

### Resource Not Found

Example:

```java
public class ResourceNotFoundException extends RuntimeException {
}
```

HTTP status:

```text
404 NOT_FOUND
```

Use this for resources that do not exist.

---

### Invalid Request / Business Validation

Example:

```java
public class InvalidRequestException extends RuntimeException {
}
```

or an equivalent project-specific exception.

HTTP status:

```text
400 BAD_REQUEST
```

Use this for request/business conditions that make the operation invalid.

---

### Conflict

Example:

```java
public class ResourceConflictException extends RuntimeException {
}
```

HTTP status:

```text
409 CONFLICT
```

Use this for conflicts such as attempting to create a resource that violates an existing business uniqueness rule.

---

### Unauthorized / Forbidden

If the existing security model requires these exceptions, map them consistently:

```text
401 UNAUTHORIZED
403 FORBIDDEN
```

Do not add security behavior if authentication/authorization is not part of the existing project.

---

### Unexpected Errors

Handle unexpected exceptions centrally.

HTTP status:

```text
500 INTERNAL_SERVER_ERROR
```

Do not expose stack traces, database errors, internal class names, or implementation details to clients.

Log the full exception internally according to the project's logging conventions.

---

# 13. ErrorResponseDto

Create a standardized error response DTO.

A recommended structure is:

```java
public record ErrorResponseDto(
    String code,
    String message,
    String description,
    String path,
    OffsetDateTime datetime,
    String correlationId,
    List<FieldErrorDto> errors
) {}
```

The exact fields may be adapted to the project's conventions.

Recommended semantics:

| Field           | Purpose                                           |
| --------------- | ------------------------------------------------- |
| `code`          | Stable application/API error code                 |
| `message`       | Short human-readable error message                |
| `description`   | More detailed explanation when appropriate        |
| `path`          | HTTP request path                                 |
| `datetime`      | Timestamp when the error was generated            |
| `correlationId` | Correlation identifier useful for troubleshooting |
| `errors`        | Optional field-level validation errors            |

A field-level error can be represented as:

```java
public record FieldErrorDto(
    String field,
    String message
) {}
```

Do not expose:

* Stack traces.
* SQL statements.
* Database schema details.
* Internal Java class names.
* Secrets.
* Authentication credentials.
* Sensitive payload data.

---

# 14. Recommended Error Codes

Use stable error codes rather than relying exclusively on exception class names.

Examples:

```text
RESOURCE_NOT_FOUND
INVALID_REQUEST
VALIDATION_ERROR
RESOURCE_CONFLICT
UNAUTHORIZED
FORBIDDEN
INTERNAL_ERROR
```

The final codes should follow existing project conventions if those already exist.

API clients should be able to use `code` programmatically without parsing the human-readable `message`.

---

# 15. Validation Error Response

Validation errors should be handled separately from generic exceptions.

For example:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponseDto> handleValidationException(
        MethodArgumentNotValidException exception,
        HttpServletRequest request) {
    ...
}
```

The response should indicate which fields failed validation.

For example:

```java
public record FieldErrorDto(
    String field,
    String message
) {}
```

The error response can contain:

```java
public record ErrorResponseDto(
    String code,
    String message,
    String description,
    String path,
    OffsetDateTime datetime,
    String correlationId,
    List<FieldErrorDto> errors
) {}
```

This is preferred over returning raw Spring validation exceptions.

Also handle parameter validation where applicable, for example:

```java
ConstraintViolationException
```

---

# 16. Exception Mapping

The `@RestControllerAdvice` should provide explicit mappings for known exception types.

Conceptually:

```text
ResourceNotFoundException
        |
        v
404 RESOURCE_NOT_FOUND

InvalidRequestException
        |
        v
400 INVALID_REQUEST

ResourceConflictException
        |
        v
409 RESOURCE_CONFLICT

MethodArgumentNotValidException
        |
        v
400 VALIDATION_ERROR

ConstraintViolationException
        |
        v
400 VALIDATION_ERROR

Unexpected Exception
        |
        v
500 INTERNAL_ERROR
```

Use the most specific exception handler available.

Do not catch `Exception` in controllers.

---

# 17. Correlation ID

The REST layer should preserve or propagate the project's correlation ID mechanism.

If the project already provides a correlation-ID filter/interceptor:

* Reuse it.
* Include the correlation ID in error responses when appropriate.
* Include it in logs.

Do not create a second, incompatible correlation-ID mechanism.

If no correlation-ID mechanism exists but the design requires one, implement the smallest component necessary according to existing project conventions.

---

# 18. HTTP Status Codes

Use HTTP status codes according to REST semantics.

Typical mappings include:

```text
200 OK
```

For successful retrieval or operations returning a response.

```text
201 CREATED
```

For successful resource creation.

```text
204 NO_CONTENT
```

For successful operations that intentionally return no body.

```text
400 BAD_REQUEST
```

For invalid request data.

```text
404 NOT_FOUND
```

For resources that do not exist.

```text
409 CONFLICT
```

For business/resource conflicts.

```text
401 UNAUTHORIZED
```

When authentication is required but missing/invalid.

```text
403 FORBIDDEN
```

When the authenticated caller is not authorized.

```text
500 INTERNAL_SERVER_ERROR
```

For unexpected server failures.

Use the endpoint contract already defined by the project rather than blindly applying these values.

---

# 19. Controller Responsibilities

A controller implementation should look conceptually like:

```java
@RestController
@RequiredArgsConstructor
public class NotificationControllerImpl
        implements NotificationController {

    private final NotificationApplicationService service;

    @Override
    public ResponseEntity<...> operation(...) {
        var result = service.execute(...);
        return ResponseEntity.ok(...);
    }
}
```

The controller must not:

* Access JPA repositories directly.
* Execute database queries directly.
* Implement retry logic.
* Implement event processing.
* Implement subscription matching.
* Perform webhook delivery.
* Implement domain state transitions.
* Catch generic exceptions.
* Construct database entities directly when an application/domain abstraction already exists.

---

# 20. Application Service Integration

The REST layer should call existing application use cases/services.

For example:

```text
Controller
    |
    v
Application Service
    |
    v
Domain
    |
    v
Existing Persistence / Infrastructure
```

If the required application service already exists, use it.

If it does not exist, create the smallest application-level use case necessary to expose the existing functionality through REST.

Do not move business logic into the controller merely because an application service is missing.

---

# 21. Domain Model Reuse

The REST layer must reuse existing domain concepts.

Examples include:

```text
Subscription
NotificationEvent
DeliveryAttempt
NotificationChannel
NotificationEventStatus
SubscriptionStatus
DeliveryAttemptStatus
```

Do not create duplicate enums such as:

```text
ApiNotificationStatus
RestNotificationStatus
ControllerNotificationStatus
```

unless there is a genuine API contract requirement.

If API values differ from domain values, introduce a dedicated mapper rather than leaking API concerns into the domain model.

---

# 22. Mapping

Use explicit mappings between REST DTOs and application/domain objects when necessary.

For example:

```text
CreateSubscriptionRequest
        |
        v
SubscriptionCommand
        |
        v
Application Service
```

and:

```text
Domain Result
        |
        v
SubscriptionResponseDto
```

The mapping logic must remain separate from the controller when it becomes non-trivial.

Use an existing mapping library/configuration if the project already uses one.

Do not introduce MapStruct or another mapping library solely for this task if the project does not already use one unless there is a clear architectural reason.

---

# 23. Pagination

For endpoints returning collections, inspect the existing API contract and project conventions.

If pagination is defined:

* Validate page number.
* Validate page size.
* Reuse existing pagination DTOs where possible.
* Avoid exposing Spring Data types directly unless this is an established project convention.
* Return stable metadata such as:

    * page
    * size
    * totalElements
    * totalPages

Do not introduce pagination if the existing API contract does not require it.

---

# 24. API DTO Design

API DTOs should be:

* Immutable.
* Explicit.
* Validated.
* Independent of JPA entities.
* Stable from an API-contract perspective.

Prefer Java records where appropriate:

```java
public record SubscriptionResponseDto(
    ...
) {}
```

Do not expose:

```java
@Entity
```

classes directly as REST responses.

This prevents persistence implementation details from becoming part of the public API.

---

# 25. OpenAPI Error Documentation

The controller interface should document the main error responses.

For example:

```java
@ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful operation"
    ),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request",
        content = @Content(
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    ),
    @ApiResponse(
        responseCode = "404",
        description = "Resource not found",
        content = @Content(
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    ),
    @ApiResponse(
        responseCode = "409",
        description = "Resource conflict",
        content = @Content(
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    ),
    @ApiResponse(
        responseCode = "500",
        description = "Internal server error",
        content = @Content(
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    )
})
```

Adapt this to the project's existing OpenAPI configuration.

Do not place these annotations on the controller implementation.

---

# 26. Existing Event Processing Must Not Be Reimplemented

The following functionality is explicitly **out of scope** for this REST implementation:

```text
Kafka event consumer
Event ingestion
Notification event processing
Subscription matching for broker events
Delivery worker pool
Retry dispatcher
Retry policy
Webhook delivery execution
Notification-event state machine implementation
```

These components already exist.

The REST layer must invoke their existing application/domain APIs when the REST contract requires interaction with them.

Do not:

* Create a second retry implementation.
* Create a second event processor.
* Create a second delivery worker.
* Create another notification-event state machine.
* Duplicate existing repository operations.

---

# 27. Persistence Layer Constraints

The persistence layer has already been generated.

Do not generate:

* Liquibase files.
* Database migrations.
* New database tables.
* New JPA repositories when an existing repository provides the required operation.

Reuse the existing:

* JPA entities.
* Repository interfaces.
* Repository adapters.
* Persistence services.

If a REST requirement reveals that an existing persistence component is missing a required operation, make the **minimum necessary change** to support the API.

Do not redesign the persistence model.

The existing database design remains:

```text
subscriptions
notification_events
delivery_attempts
```

There is no:

```text
deliveries
```

table/entity.

---

# 28. Security

Inspect the existing project security configuration before implementing endpoint-level security.

If Spring Security is already configured:

* Reuse the existing configuration.
* Apply existing authentication/authorization conventions.
* Do not bypass security from the controller.

If security is not part of the existing design, do not introduce an unrelated security architecture solely for this task.

---

# 29. Testing Requirements

Generate REST-layer tests covering at least:

## Controller tests

Verify:

* Correct HTTP status.
* Correct response body.
* Correct application-service invocation.
* Correct request mapping.
* Validation behavior.
* Error handling.

Use the project's existing testing conventions, such as:

```text
JUnit 5
Mockito
MockMvc
Spring Boot Test
```

as appropriate.

---

## Validation tests

Verify invalid:

* Required fields.
* Blank values.
* Invalid enum values.
* Invalid identifiers.
* Invalid pagination parameters.
* Invalid formats.

Verify that invalid requests do not invoke the application service.

---

## Exception-handler tests

Verify mappings such as:

```text
ResourceNotFoundException -> 404
InvalidRequestException -> 400
ResourceConflictException -> 409
MethodArgumentNotValidException -> 400
ConstraintViolationException -> 400
Unexpected Exception -> 500
```

Verify that the standardized `ErrorResponseDto` is returned.

Verify that internal implementation details are not exposed.

---

## OpenAPI validation

If the project already has OpenAPI contract tests or documentation tests, extend them to cover the new endpoints.

Do not create a separate OpenAPI configuration.

---

# 30. Code Quality Requirements

The generated code must:

* Target Java 21.
* Follow the existing Spring Boot version and project conventions.
* Prefer constructor injection.
* Keep controllers thin.
* Keep business logic outside controllers.
* Reuse existing domain/application components.
* Reuse existing enums.
* Reuse existing persistence components.
* Use Jakarta Bean Validation.
* Provide centralized exception handling.
* Provide consistent error responses.
* Document endpoints through the controller interface.
* Avoid duplicated OpenAPI annotations.
* Avoid exposing JPA entities through REST.
* Avoid generic exception handling inside controllers.
* Avoid leaking internal errors to clients.
* Avoid unnecessary dependencies.

---

# 31. Implementation Procedure for the Coding Agent

Before writing code, the agent must:

1. Inspect the existing project structure.
2. Identify existing REST controllers/interfaces.
3. Identify existing endpoint contracts.
4. Inspect the existing OpenAPI configuration.
5. Identify existing DTOs.
6. Identify existing application services/use cases.
7. Identify existing domain entities and enums.
8. Identify existing JPA repositories and persistence services.
9. Identify the existing exception hierarchy, if any.
10. Identify existing validation conventions.
11. Identify existing correlation-ID handling.
12. Identify existing security conventions.
13. Reuse existing components whenever possible.
14. Generate only the missing REST components.
15. Add/modify tests.
16. Avoid modifying event-processing and retry components unless a REST integration point is genuinely missing.

---

# 32. Definition of Done

The REST implementation is complete when:

* [ ] All REST endpoints defined by the existing notification-service design are implemented.
* [ ] Controller contracts are defined through interfaces.
* [ ] OpenAPI documentation is placed on controller interfaces.
* [ ] Controller implementations contain no OpenAPI documentation.
* [ ] Request DTOs are validated.
* [ ] Path/query parameters are validated where required.
* [ ] Existing domain/application components are reused.
* [ ] Existing event-processing components are reused.
* [ ] Existing retry components are reused.
* [ ] Existing JPA repositories are reused.
* [ ] Existing enums are reused.
* [ ] No duplicate domain model has been introduced unnecessarily.
* [ ] A global `@RestControllerAdvice`/`@ControllerAdvice` exists.
* [ ] Custom exceptions exist for the relevant API error categories.
* [ ] `ErrorResponseDto` provides a standardized error structure.
* [ ] Validation errors are represented consistently.
* [ ] Error responses contain appropriate HTTP status codes.
* [ ] Internal implementation details are not exposed.
* [ ] Correlation IDs are preserved where supported by the project.
* [ ] REST endpoints do not contain business logic.
* [ ] REST endpoints do not access repositories directly.
* [ ] No event-processing/retry implementation has been duplicated.
* [ ] No `deliveries` table/entity has been introduced.
* [ ] No unnecessary database/Liquibase changes have been introduced.
* [ ] Unit/integration tests cover successful requests, validation failures, and exception mappings.
* [ ] The implementation compiles and passes the project's existing test suite.
