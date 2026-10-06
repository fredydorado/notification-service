# AGENTS.md — notification-service

Single-module Spring Boot 4.1.1 / Java 21 / Maven service (`com.fardorado.notification`). Kafka ingestion, subscription matching, webhook delivery and database-backed retries are implemented behind a hexagonal architecture; the requirements and C4 design live in `docs/spec/`, and `README.md` covers building, running and configuring the service.

## Commands

- No Maven wrapper (`mvnw` absent) — use the system `mvn`.
- Run app: `mvn spring-boot:run`
- All tests: `mvn test` — requires a working Docker daemon (Testcontainers).
- Single test class: `mvn test -Dtest=NotificationServiceApplicationTests`
- Single method: `mvn test -Dtest=NotificationServiceApplicationTests#contextLoads`
- Package: `mvn package`

## Critical context

- **Tests need Docker.** `TestcontainersConfiguration` starts a PostgreSQL container (`postgres:latest`) and a Kafka container (`apache/kafka:latest`) via `@ServiceConnection`; `mvn test` fails without a *reachable* Docker daemon. The first run pulls both images (slow). Unit tests alone: `mvn test -Dtest='!*IntTest,!NotificationServiceApplicationTests' -DfailIfNoSpecifiedTests=false`.
- **Docker may need `DOCKER_HOST`.** Where Docker runs inside a WSL2 distro instead of Docker Desktop, the Windows JVM cannot find it and every integration test fails the Spring context with `Could not find a valid Docker environment`. Keep the distro alive for the whole run (`wsl -d Ubuntu -- bash -c 'sleep 5400'` in its own terminal — WSL2 stops a distro the moment its last command exits) and pass `DOCKER_HOST=tcp://<wsl-ip>:2375` using the first address from `wsl -d Ubuntu -- hostname -I`, as a literal IPv4 rather than `localhost`. See README.md for the `socat` bridge.
- **Jackson 3, not Jackson 2.** Spring Boot 4 auto-configures `tools.jackson.databind.json.JsonMapper`. Jackson 2 (`com.fasterxml.jackson.databind`) is on the classpath only transitively and has **no bean**, so injecting its `ObjectMapper` compiles and then fails at startup with `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`. Annotations stay in `com.fasterxml.jackson.annotation`.
- **`windows-unixdomain-tmpdir` profile in `pom.xml`.** On Windows setups where security software holds `%TEMP%`, the AF_UNIX socket behind `Selector.open()` cannot be connected to, and anything selector-based (JDK `HttpServer` test stubs, Kafka clients, Testcontainers) dies with `Unable to establish loopback connection`. The profile redirects `jdk.net.unixdomain.tmpdir` to `target/`. Do not "simplify" it away; `-Djava.io.tmpdir` is not an equivalent, since `UnixDomainSockets` reads the `TEMP` environment variable.
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
- Persistence: PostgreSQL. Liquibase owns the schema (`src/main/resources/db/changelog`, three tables: `subscription`, `notification_event`, `delivery_attempt` — deliberately no `deliveries` table) and `ddl-auto` is `validate`, so entity/column mismatches fail at startup.
- API docs: springdoc-openapi 3.1.0.
