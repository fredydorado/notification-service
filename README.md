# notification-service

Consumes notification events from Kafka, matches them against active client
subscriptions and delivers them to the subscriber's webhook, with durable,
database-backed retries.

Single-module **Spring Boot 4.1.1 / Java 21 (VirtualThreads) / Maven** service, package root
`com.fardorado.notification`, built on a hexagonal (ports & adapters)
architecture.

---

## How this project was built

This service was implemented from the software designs in
[`docs/spec/design`](docs/spec/design): the C4 model under
[`docs/spec/design/c4/`](docs/spec/design/c4) — `C4_01_SystemContext.png`,
`C4_02_Container.png`, `C4_03_Components.png`, `C4_04_DatabaseSchema.png`,
`C4_KeyDecisionsAndAssumptions.png` — and the event lifecycle in
[`notification-event-state-machine.png`](docs/spec/design/notification-event-state-machine.png).
Those designs were themselves created from the requirements in
[`docs/spec/SrSoftwareEngineer_Notifications.pdf`](docs/spec/SrSoftwareEngineer_Notifications.pdf).

The project was also **designed and developed primarily using AI coding agents**
(OpenCode and Claude Code):

- The agents generated the majority of the code, working from the design diagrams in
  `docs/spec/design` and the feature specifications in
  [`docs/spec/features/`](docs/spec/features) —
  [`001_createPersistenceComponents.md`](docs/spec/features/001_createPersistenceComponents.md),
  [`002_EventProcessingComponents.md`](docs/spec/features/002_EventProcessingComponents.md)
  and [`003_RestNotificationService.md`](docs/spec/features/003_RestNotificationService.md).
- They worked within the architecture, naming and project rules defined in
  [`docs/ai/rules/`](docs/ai/rules) — `ARCHITECTURE_CONVENTIONS.md`,
  `NAMING_CONVENTIONS.md` and `PROJECT_GUIDELINES.md` — which are enforced
  conventions, not suggestions.

Finally, there are some important considerations:
- There is docs/owasp/OWASP_TOP_10_ANALYSIS.md file with a basic analysis of vulnerabilities of the current service.
- There are some one of the requirements was to use the src/test/resources/sample/notification_events.json to test the solution. 
So this file was used to test this specific scenario in KafkaNotificationConsumerIntTest. 

---

## Requirements

| | |
|---|---|
| JDK | 21 |
| Maven | 3.9+ (no wrapper — use the system `mvn`) |
| Docker | required for `mvn test` (Testcontainers) and to [run the service locally](#run-locally) (Docker Compose) |

---

## Build and run

```bash
mvn package
```

The service needs a PostgreSQL and a Kafka broker. For local work, follow
[Run locally](#run-locally): one command starts both in Docker and a Spring profile
points the service at them. Once the service is up, the interactive API documentation
is at <http://localhost:8080/swagger-ui.html>.

Liquibase owns the schema and `spring.jpa.hibernate.ddl-auto` is `validate`, so
Hibernate never creates tables.

---

## Run locally

[`docker-compose.yml`](docker-compose.yml) starts the infrastructure and the `local`
Spring profile ([`application-local.yaml`](src/main/resources/application-local.yaml))
points the service at it. Besides JDK 21 and Maven, the only thing you need is Docker
with Compose v2 — no PostgreSQL or Kafka has to be installed on the machine.

| Service | Image | Host port | Connection |
|---|---|---|---|
| Kafka | `apache/kafka:latest` — default single-node KRaft configuration | `9092` | `localhost:9092`, plaintext |
| PostgreSQL | `postgres:17` | **`5433`** (container port 5432) | database `notification_service`, user `postgres`, password `postgres` |

PostgreSQL is published on **5433**, not 5432, so it does not collide with a PostgreSQL
already installed on the host.

### 1. Start Kafka and PostgreSQL

```bash
docker compose up -d --wait
```

`--wait` returns once both healthchecks pass. The first run pulls the images. There is
no topic to create: the broker auto-creates `notification-events` the first time the
service or a producer touches it (so one "recoverable issue" metadata warning from the
Kafka client at the very first start is expected).

### 2. Start the service with the `local` profile

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

PowerShell needs the argument quoted:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

Setting `SPRING_PROFILES_ACTIVE=local` works as well. Liquibase creates the three tables
on the first start. Check that everything is connected:

```bash
curl -s http://localhost:8080/actuator/health
```

The `db` component is a real probe against the PostgreSQL container, and the Kafka
consumer logs `partitions assigned: [notification-events-0]`.

> **Windows with the `%TEMP%` problem.** If startup fails with
> `Unable to establish loopback connection` (see
> [Why the pom has a `windows-unixdomain-tmpdir` profile](#why-the-pom-has-a-windows-unixdomain-tmpdir-profile)),
> the forked JVM needs the same workaround the test run gets from the pom. The pom
> profile only configures Surefire, so pass it explicitly:
>
> ```powershell
> mvn spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=target"
> ```

> **Docker inside WSL2 instead of Docker Desktop.** Run Compose inside the distro (and keep
> the distro awake, as described under [Running the tests](#running-the-tests)), for
> example `wsl -d Ubuntu -- docker compose up -d --wait` from this directory. Windows reaches
> the published ports through WSL's `localhost` forwarding.

### 3. Try it end to end

A client only receives notifications it is subscribed to, so create a subscription first;
an event with no matching subscription is stored as `FAILED` with no owner and is not
visible through the API. The `event_type` is stored in its wire form (lowercase, as it
appears in Kafka messages).

**a. A webhook to deliver to.** Use any endpoint that answers `2xx`. A throwaway receiver
(save as `webhook_receiver.py`, run `python webhook_receiver.py`):

```python
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        print("received:", body.decode(), flush=True)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

HTTPServer(("", 9100), Handler).serve_forever()
```

**b. A subscription for `CLIENT001`:**

```bash
docker exec notification-postgres psql -U postgres -d notification_service -c "INSERT INTO subscription (client_id, event_type, status, webhook_url, created_at, updated_at) VALUES ('CLIENT001', 'credit_card_payment', 'ACTIVE', 'http://localhost:9100/webhook', now(), now());"
```

**c. Publish an event** (one JSON document per line — the sample file
`src/test/resources/sample/notification_events.json` wraps many events in an `events`
array, so publish them one at a time). In Git Bash on Windows, prefix the command with
`MSYS_NO_PATHCONV=1`, otherwise `/opt/kafka/...` is rewritten into a Windows path.

```bash
echo '{"event_id":"EVT001","event_type":"credit_card_payment","content":"Credit card payment received for $150.00","client_id":"CLIENT001"}' | docker exec -i notification-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic notification-events
```

**d. Follow it through the API:**

```bash
curl -s -H 'X-Client-Id: CLIENT001' http://localhost:8080/notification_events/EVT001
```

Within a few seconds the status is `COMPLETED` with one `SUCCESS` attempt (`httpStatus` 200)
and the receiver prints the delivered payload. If the webhook is unreachable, the event
instead goes through `RETRY_SCHEDULED` with exponential backoff and ends `FAILED` after
`notification.processing.max-attempts` — which is also the way to try
`POST /notification_events/EVT001/replay`.

### Credentials, ports and other hosts

- Connect with any PostgreSQL client at `localhost:5433` — for example
  `docker exec -it notification-postgres psql -U postgres -d notification_service`.
- `DB_USERNAME` and `DB_PASSWORD` configure **both** the container and the service, so
  export them before both `docker compose up` and `mvn spring-boot:run`. PostgreSQL reads
  them only when it initialises an empty data directory, so after changing them on an
  existing volume run `docker compose down -v` first.
- To point the service somewhere else, edit `application-local.yaml` or override
  `SPRING_DATASOURCE_URL` / `SPRING_KAFKA_BOOTSTRAP_SERVERS`.

### Stop and reset

```bash
docker compose down
```

keeps the database (it lives in the named volume `notification-postgres-data`); add `-v`
to delete it and start from an empty schema next time. Kafka data is not persisted.

---

## Testing the REST API with Postman

[`postman/`](postman) holds a Postman collection that exercises the three endpoints of
the [locally deployed](#run-locally) service, mirroring the scenarios of
`NotificationEventControllerIntTest`:

| File | Purpose |
|---|---|
| [`notification-service.postman_collection.json`](postman/notification-service.postman_collection.json) | 30 requests with test scripts, in folders: Health, List, Detail, Replay, Errors, Correlation id, OpenAPI |
| [`seed.sql`](postman/seed.sql) | The data those requests expect: two clients (`CLIENT-API-A`, `CLIENT-API-B`) and events in every status needed |

The integration test seeds its data through the repositories; Postman can only make HTTP
calls, so the same data is loaded with a SQL script instead.

### Setup

1. Start the infrastructure: `docker compose up -d --wait`.
2. Start the service **with the dispatcher disabled**, so a replayed event stays `PENDING`
   long enough to be asserted instead of being claimed and delivered straight away (this is
   what the integration test does too):

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--notification.processing.dispatch-enabled=false
   ```

   (PowerShell: quote each `-D` argument, as in [Run locally](#2-start-the-service-with-the-local-profile).)
3. Load the test data — this is also how you reset it:

   ```bash
   docker exec -i notification-postgres psql -U postgres -d notification_service < postman/seed.sql
   ```

4. In Postman, **Import** the collection file. Its collection variables default to
   `baseUrl = http://localhost:8080`, `clientA = CLIENT-API-A` and `clientB = CLIENT-API-B`;
   change `baseUrl` if the service listens elsewhere.
5. Run it: right-click the collection → **Run collection** (the folders are ordered, and
   every request carries its own assertions), or send requests one by one.

Command-line equivalent, with [newman](https://github.com/postmanlabs/newman):

```bash
newman run postman/notification-service.postman_collection.json
```

### What is covered

| Folder | Scenarios |
|---|---|
| Health | `/actuator/health` is `UP` including the database |
| List | only the caller's events; pagination metadata; attempt count + last HTTP status; `delivery_status` filter; `from` / `to` creation-date range |
| Detail | event with attempt history (attempts `1, 2`, HTTP `500, 503`, webhook URL); another client's event is `404`; unknown event is `404` |
| Replay | `FAILED` event accepted (`202`) and re-queued as `PENDING`; `COMPLETED` event rejected (`409`, `NOTIFICATION_EVENT_NOT_REPLAYABLE`) and untouched; another client's event is `404` and stays `FAILED`; replay twice: first `202`, second `409`, and no delivery attempts are fabricated |
| Errors | missing `X-Client-Id` → `401`; negative page, zero size, unknown `delivery_status`, malformed date → `VALIDATION_ERROR`; size above the maximum and an inverted date range → `INVALID_REQUEST`; error body is well formed and leaks no class names, SQL or stack traces |
| Correlation id | a supplied `X-Correlation-Id` is echoed; one is generated otherwise |
| OpenAPI | `/v3/api-docs` documents all three endpoints, the error schema and the `202`/`404`/`409` responses |

Two things differ from the integration test:

- **Concurrent replays.** Postman cannot send two requests at the same instant, so the
  test that proves a race produces exactly one winner is replaced by a sequential
  double replay (`202`, then `409`). The race itself stays covered by
  `shouldAcceptOnlyOneOfTwoConcurrentReplays`.
- **Database assertions.** The integration test reads the row back with JDBC; the collection
  reads it back through `GET /notification_events/{id}` instead.

The **Replay** folder consumes the seeded `FAILED` events (`FAILED → PENDING`), so run
`postman/seed.sql` again before every full run. Without the dispatcher disabled, the
"re-queued (PENDING)" check can fail, because the event may already have been picked up.

---

## Running the tests

The suite is **123 tests**: unit tests plus integration tests that run against a real
PostgreSQL (`postgres:latest`) and a real Kafka (`apache/kafka:latest`) started by
Testcontainers. All of them pass; if they fail on your machine, it is almost always
because the JVM cannot reach a Docker daemon.

### Linux and macOS

With a native Docker daemon running, nothing special is needed:

```bash
mvn test
```

### Windows

It depends on how Docker is installed.

**With Docker Desktop**, `mvn test` works as-is — Testcontainers finds the daemon over
its named pipe.

**With Docker running inside a WSL2 distro** (the setup on this project's dev machine),
plain `mvn test` fails *every* integration test with:

```
Caused by: java.lang.IllegalStateException: Could not find a valid Docker environment.
```

This is not a problem with the tests. Two things are missing at run time:

1. **The distro has to stay up.** WSL2 stops an idle distro and takes the daemon with
   it — including part way through a run, which shows up as an intermittent
   `Connection refused` or `DOCKER_HOST ... is not listening`.
2. **The JVM has to be told where the daemon is.** There is no named pipe to discover,
   so Testcontainers needs `DOCKER_HOST`.

Use the runner script, which handles both and passes everything through to Maven:

```powershell
.\scripts\run-tests.ps1
```

```bash
./scripts/run-tests.sh
```

Any Maven arguments work as usual:

```powershell
.\scripts\run-tests.ps1 test -Dtest=EventProcessingFlowIntTest
```

```powershell
.\scripts\run-tests.ps1 package
```

The script holds a WSL session open for exactly the length of the run, starts the
`docker-tcp-proxy` bridge if it is not already up, resolves the distro's current IP,
exports `DOCKER_HOST`, runs Maven, and then releases the session. Expect output like:

```
[1/4] Holding Ubuntu open for the duration of the run...
[2/4] Checking the Docker daemon and the docker-tcp-proxy bridge...
      Docker Engine 25.0.5
[3/4] Resolving the Ubuntu address...
      DOCKER_HOST=tcp://172.18.63.240:2375
[4/4] mvn test
```

`scripts/run-tests.sh` is safe to use everywhere: where a daemon is already reachable
(Linux, macOS, Docker Desktop, or `DOCKER_HOST` already set) it is a thin passthrough
to Maven.

Point the scripts at a different distro or port with environment variables:

```powershell
$env:WSL_DISTRO = 'Ubuntu-22.04'; $env:DOCKER_BRIDGE_PORT = '2375'
```

#### Doing it by hand

If you would rather not use the script, the same thing in two terminals:

```bash
wsl -d Ubuntu -- bash -c 'sleep infinity'
```

```bash
DOCKER_HOST=tcp://172.18.63.240:2375 mvn test
```

Take the address from the **first** entry of `wsl -d Ubuntu -- hostname -I`. Use the
literal IPv4 — the JVM may resolve `localhost` to `::1` and report the port closed.
WSL assigns the address, so it can change when the distro restarts; re-read it rather
than hard-coding it. The one-off bridge, if it does not exist yet:

```bash
wsl -d Ubuntu -- docker run -d --name docker-tcp-proxy --restart unless-stopped --network host -v /var/run/docker.sock:/var/run/docker.sock alpine/socat TCP-LISTEN:2375,fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock
```

### Unit tests only

These need no Docker at all, on any platform:

```bash
mvn test "-Dtest=!*IntTest,!NotificationServiceApplicationTests" -DfailIfNoSpecifiedTests=false
```

### Narrowing a run

```bash
mvn test -Dtest=EventProcessingFlowIntTest
```

```bash
mvn test -Dtest=NotificationEventTest#shouldRejectInvalidTransition
```

### Why the pom has a `windows-unixdomain-tmpdir` profile

On some Windows setups `java.nio.channels.Selector.open()` fails in *every* JVM with
`IOException: Unable to establish loopback connection`. The JDK backs the selector's
wakeup pipe with an AF_UNIX socket created in `%TEMP%`; where security software holds
that folder, the socket binds but cannot be connected to. That breaks anything
selector-based — the JDK `HttpServer` webhook stubs in the tests, Kafka clients,
Testcontainers.

The Windows-only profile in [`pom.xml`](pom.xml) redirects the JDK's AF_UNIX scratch
directory to `target/`:

```
-Djdk.net.unixdomain.tmpdir=${project.build.directory}
```

Setting `-Djava.io.tmpdir` does **not** help — `UnixDomainSockets` reads the `TEMP`
environment variable, not that property. Fixing `TMP`/`TEMP` at the OS level fixes it
machine-wide. Linux and CI runs keep the platform default.

---

## Configuration

All values live in [`src/main/resources/application.yaml`](src/main/resources/application.yaml).
The connection settings for the [local deployment](#run-locally) (PostgreSQL on `localhost:5433`,
Kafka on `localhost:9092`) are in [`application-local.yaml`](src/main/resources/application-local.yaml),
which is only active with the `local` profile.

| Property | Default | Meaning |
|---|---|---|
| `notification.api.default-page-size` | `20` | Page size when a request does not specify one |
| `notification.api.max-page-size` | `100` | Largest page a client may request (larger is rejected with 400) |
| `notification.kafka.topic` | `notification-events` | Topic the consumer subscribes to |
| `notification.processing.dispatch-enabled` | `true` | Set `false` to disable the retry/delivery scheduler (used by tests that assert on `PENDING` rows) |
| `notification.processing.poll-interval` | `5s` | How often the dispatcher sweeps for deliverable work |
| `notification.processing.max-attempts` | `5` | Delivery attempts before an event is failed permanently |
| `notification.processing.backoff-initial` | `30s` | First retry delay (exponential from there) |
| `notification.processing.backoff-max` | `10m` | Backoff ceiling |
| `notification.processing.lease-timeout` | `5m` | After this, a `DELIVERING` event counts as stale and is recovered |
| `notification.processing.claim-batch-size` | `50` | Events claimed per dispatcher sweep |
| `notification.processing.worker-pool-size` | `10` | Bound on concurrent webhook calls |

Kafka consumption uses manual acknowledgement (`ack-mode: manual_immediate`,
`enable-auto-commit: false`): a message is acknowledged **only after** the
notification event has been durably persisted.

---

## Observability

Actuator is on the classpath (`spring-boot-starter-actuator`), with
`micrometer-registry-prometheus` as a **runtime-only** dependency. Four endpoints are
exposed, configured under `management:` in
[`application.yaml`](src/main/resources/application.yaml):

| Endpoint | What it gives you |
|---|---|
| <http://localhost:8080/actuator/health> | Status with component detail — `db` (a real probe against PostgreSQL), `diskSpace`, `ping`, `ssl` |
| <http://localhost:8080/actuator/info> | Application name and version, from `META-INF/build-info.properties` |
| <http://localhost:8080/actuator/metrics> | Meter names as JSON; append one (`/actuator/metrics/jvm.memory.used`) to drill in |
| <http://localhost:8080/actuator/prometheus> | Every meter in Prometheus text format |

Nothing external has to be running for any of this. `micrometer-registry-prometheus` is
a client library that renders the in-process registry as text; it does not talk to a
Prometheus server, and none has to exist:

```bash
curl -s http://localhost:8080/actuator/prometheus | head -40
```

There is no custom instrumentation yet — the meters come from the auto-configured
Micrometer binders, which on this classpath cover:

| Meters | Useful for |
|---|---|
| `http_server_requests_*` | REST latency and status mix, per endpoint |
| `hikaricp_connections_*` | Connection-pool pressure from the dispatcher's worker pool |
| `kafka_consumer_*` | Ingestion lag and fetch behaviour on `notification-events` |
| `jvm_*`, `system_*`, `process_*` | Heap, GC pauses, threads, CPU |
| `tomcat_*` | Servlet container threads and sessions |

Every meter carries an `application` tag (`notification-service`), so several instances
stay apart in a single scrape.

### A deliberate note on exposure

`management.endpoints.web.exposure.include` is a **curated list**, not `*`. This service
has no Spring Security (see [Client identity](#client-identity)), so every exposed
endpoint is reachable without credentials — and `management.endpoint.health.show-details`
is `always`, so the health body names its components. Both are fine for local work and
for a deployment where `/actuator/**` is not routed from outside. Before exposing the
service publicly, put the endpoints behind the ingress or move them to a separate
`management.server.port`.

---

## REST API

Three endpoints. With the service running locally (`mvn spring-boot:run`), the
OpenAPI documentation is served at:

| | |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |

These are the springdoc defaults (`springdoc-openapi-starter-webmvc-ui`, see
[`pom.xml`](pom.xml)): `application.yaml` overrides neither `server.port` nor any
`springdoc.*` path. The API title and version come from
[`OpenApiConfiguration`](src/main/java/com/fardorado/notification/configuration/OpenApiConfiguration.java).

| Method | Endpoint | Purpose | Success |
|---|---|---|---|
| `GET` | `/notification_events` | List the calling client's events | `200` |
| `GET` | `/notification_events/{notification_event_id}` | Event details + attempt history | `200` |
| `POST` | `/notification_events/{notification_event_id}/replay` | Replay a failed event | `202` |

`{notification_event_id}` is the business `event_id` (e.g. `EVT001`), consistent with the
`event_id == notification_event_id` invariant. The internal surrogate key is never exposed.

### Client identity

There is no Spring Security in this service. Every request must carry an **`X-Client-Id`**
header, which stands in for the authenticated principal; a missing header is `401`. A
`client_id` query parameter is deliberately **not** accepted. Ownership is resolved through
`notification_event.subscription_id -> subscription.client_id` and enforced in the
application layer, so another client's event is reported as `404` rather than `403` — its
existence is never revealed. Events that matched no subscription have no owner and are
therefore invisible to every client.

```bash
curl -s -H 'X-Client-Id: CLIENT001'   'http://localhost:8080/notification_events?delivery_status=FAILED&page=0&size=20'
```

### Listing

Optional filters: `from`, `to` (ISO-8601 instants, inclusive, on creation time) and
`delivery_status`. Paged with `page` (default `0`) and `size` (default `20`, max `100`);
a larger `size` is rejected rather than silently clamped. Results are newest first.

```json
{
  "items": [
    { "id": "EVT001", "eventType": "CREDIT_CARD_PAYMENT",
      "createdAt": "2026-10-05T15:30:00Z", "status": "FAILED",
      "attemptCount": 3, "lastHttpStatus": 500 }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### Replay

Replay is allowed only from the terminal `FAILED` state and is asynchronous: it performs
the `FAILED -> PENDING` transition, returns `202`, and the existing dispatch scheduler picks
the event up on its next sweep. No webhook call happens on the request thread. Any other
state yields `409` with code `NOTIFICATION_EVENT_NOT_REPLAYABLE`. Concurrent replays are
resolved by the existing optimistic locking, so exactly one succeeds and the event is never
replayed twice.

### Errors

Every failure returns the same body, and never a stack trace, SQL or internal class name:

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Resource not found",
  "description": "Notification event EVT999 not found",
  "path": "/notification_events/EVT999",
  "datetime": "2026-10-05T15:30:00.123Z",
  "correlationId": "0f1c...",
  "errors": null
}
```

Codes: `RESOURCE_NOT_FOUND` (404), `INVALID_REQUEST` (400), `VALIDATION_ERROR` (400, with
`errors[]`), `RESOURCE_CONFLICT` / `NOTIFICATION_EVENT_NOT_REPLAYABLE` (409), `UNAUTHORIZED`
(401), `INTERNAL_ERROR` (500).

Requests may supply an `X-Correlation-Id` header; one is generated when absent. It is echoed
on the response, included in error bodies and put in the logging MDC — the same header and
MDC key the webhook client and Kafka consumer already use.

---

## How it works

```
Kafka ──▶ KafkaNotificationConsumer ──▶ ProcessNotificationEventUseCase
                                             │  (idempotent on event_id)
                                             ▼
                                      notification_event (PENDING)
                                             │
       NotificationEventDispatchScheduler ──▶ DeliveryClaimService
                                             │  FOR UPDATE SKIP LOCKED
                                             ▼
                                      DELIVERING + delivery_attempt
                                             │
                                      WebhookChannelClient  ◀── bounded worker pool
                                             │
                                      DeliveryResultService + RetryPolicy
                                             │
                        COMPLETED  /  RETRY_SCHEDULED  /  FAILED
```

Key invariants:

- `event_id` from the broker **is** the notification event's identity; a repeated
  `event_id` never creates a second notification event.
- Delivery attempts are append-only — a retry adds a record, it never overwrites
  one.
- Retry state lives in the database (`status` + `next_attempt_at`), so it
  survives restarts. There is no in-memory retry queue.
- The webhook HTTP call happens outside any database transaction.
- Events stuck in `DELIVERING` past the lease are recovered through the retry
  policy, never silently completed.

### Data model

Three tables, owned by Liquibase under
[`src/main/resources/db/changelog`](src/main/resources/db/changelog):
`subscription`, `notification_event`, `delivery_attempt`. There is deliberately
no `deliveries` table.

| Entity | Statuses |
|---|---|
| `subscription` | `ACTIVE`, `INACTIVE` |
| `notification_event` | `PENDING`, `DELIVERING`, `RETRY_SCHEDULED`, `COMPLETED`, `FAILED` |
| `delivery_attempt` | `IN_PROGRESS`, `SUCCESS`, `FAILED` |

Each `delivery_attempt` also records the `http_status` the webhook returned; it is
`NULL` when no response arrived (connection failure, timeout, or an attempt recovered
after exceeding the delivery lease).

All three carry a `version` column for optimistic locking.

---

## Project layout

```
docker-compose.yml  Kafka + PostgreSQL for running the service locally
postman/         Postman collection + seed.sql for testing the REST API against it
scripts/         run-tests.ps1 / run-tests.sh - Maven with a reachable Docker daemon
src/main/java/com/fardorado/notification/
  domain/        model + state machine; no Spring, JPA, Kafka or Jackson
  application/   use cases, commands, ports (in/out), domain services
  adapter/in/    web (REST controllers, DTOs, advice), messaging (Kafka consumer), scheduler
  adapter/out/   persistence (JPA entities, repositories, mappers), webhook client
  configuration/ properties, worker pool, scheduling
```

Dependencies point inward: `adapter → application → domain`.

---

## Documentation

- `docs/spec/SrSoftwareEngineer_Notifications.pdf` — the original requirements
- `docs/spec/design/` — software design: C4 model (`c4/`) and the notification event
  state machine
- `docs/spec/features/` — per-feature specifications
- `docs/ai/rules/` — enforced architecture, naming and project conventions
- `AGENTS.md` — orientation for coding agents

See [How this project was built](#how-this-project-was-built) for how these documents
drove the implementation.

---

## Notes for contributors

- **Jackson 3.** Spring Boot 4 auto-configures `tools.jackson.databind.json.JsonMapper`.
  Jackson 2 (`com.fasterxml.jackson.databind`) is only a transitive dependency and
  has no bean — injecting `ObjectMapper` from it will compile and then fail at
  startup. Annotations stay in `com.fasterxml.jackson.annotation`.
- **Lombok and MapStruct** are wired through `annotationProcessorPaths` in
  *both* the `default-compile` and `default-testCompile` executions of
  `maven-compiler-plugin`. Adding a processor means editing both.
- **`TestRestTemplate` moved in Boot 4** to `org.springframework.boot.resttestclient`, is
  no longer auto-registered (annotate the test `@AutoConfigureTestRestTemplate`) and needs
  `spring-boot-restclient` on the test classpath for `RestTemplateBuilder`.
- Integration tests run the full Spring context against Testcontainers
  (`RANDOM_PORT`, `TestRestTemplate`), never MockMvc.
