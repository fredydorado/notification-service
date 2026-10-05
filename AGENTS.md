# AGENTS.md — notification-service

Single-module Spring Boot 4.1.1 / Java 21 / Maven service (`com.fardorado.notification`). Currently a skeleton (only the bootstrap class); the full requirements and C4 design live in `docs/spec/`.

## Commands

- No Maven wrapper (`mvnw` absent) — use the system `mvn`.
- Run app: `mvn spring-boot:run`
- All tests: `mvn test` — requires a working Docker daemon (Testcontainers).
- Single test class: `mvn test -Dtest=NotificationServiceApplicationTests`
- Single method: `mvn test -Dtest=NotificationServiceApplicationTests#contextLoads`
- Package: `mvn package`

## Critical context

- **Tests need Docker.** `TestcontainersConfiguration` starts a PostgreSQL container (`postgres:latest`) and a Kafka container (`apache/kafka:latest`) via `@ServiceConnection`; `mvn test` fails without a running Docker daemon. The first run pulls the Kafka image (slow).
- **Per-technology starter pattern.** The pom uses `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-kafka`, and `spring-boot-resttestclient`. Follow this pattern when adding dependencies.
- **`TestRestTemplate` moved in Boot 4.** It lives in `org.springframework.boot.resttestclient` (artifact `spring-boot-resttestclient`), not in Boot 3's `org.springframework.boot.test.web.client`.
- **Lombok is wired manually.** Annotation processing is configured via `annotationProcessorPaths` in `maven-compiler-plugin` (both `default-compile` and `default-testCompile`). Adding another annotation processor (e.g. MapStruct) means editing both executions in `pom.xml`.

## Mandatory rule docs (already loaded via `opencode.json` → `docs/ai/rules/*.md`)

Read before writing any code — these are enforced conventions, not suggestions:

- `ARCHITECTURE_CONVENTIONS.md` — hexagonal architecture (ports & adapters). Dependencies point inward: adapter → application → domain. Domain has zero Spring/JPA/Kafka/Jackson dependencies. Separate `Notification` (domain) from `NotificationEntity` (persistence).
- `NAMING_CONVENTIONS.md` — suffix rules: `*UseCase`/`*UseCaseImpl`, `*RepositoryImpl`, `*JpaRepository`, `*Controller`/`*ControllerImpl`, `*IntTest`, `*RequestDto`/`*ResponseDto`, `*Command`, `*Result`, `*Entity`; the `Jpa` infix is reserved for `*JpaRepository` only; no `Rest` prefix on controllers; domain services are plain classes (no interface + impl pairs).
- `PROJECT_GUIDELINES.md` — integration tests run the full Spring context (`RANDOM_PORT`, `TestRestTemplate`, Testcontainers), never MockMvc. Controllers are interfaces carrying the OpenAPI docs, with implementations in a sibling `impl` package.

## Stack notes

- Messaging: Kafka (`spring-boot-starter-kafka`).
- Persistence: PostgreSQL (runtime driver only; schema is not yet defined).
- API docs: springdoc-openapi 3.1.0.
