# notification-service

Consumes notification events from Kafka, matches them against active client
subscriptions and delivers them to the subscriber's webhook, with durable,
database-backed retries.

Single-module **Spring Boot 4.1.1 / Java 21 / Maven** service, package root
`com.fardorado.notification`, built on a hexagonal (ports & adapters)
architecture.

---

## Requirements

| | |
|---|---|
| JDK | 21 |
| Maven | 3.9+ (no wrapper — use the system `mvn`) |
| Docker | required for `mvn test` only (Testcontainers) |

---

## Build and run

```bash
mvn package
```

```bash
mvn spring-boot:run
```

The app expects a reachable PostgreSQL and Kafka; Liquibase owns the schema and
`spring.jpa.hibernate.ddl-auto` is `validate`, so Hibernate never creates tables.

---

## Running the tests

The unit tests need nothing special:

```bash
mvn test -Dtest='!*IntTest,!NotificationServiceApplicationTests' -DfailIfNoSpecifiedTests=false
```

The integration tests (`*IntTest` plus `NotificationServiceApplicationTests`)
start a real PostgreSQL (`postgres:latest`) and Kafka (`apache/kafka:latest`)
through Testcontainers, so **`mvn test` needs a reachable Docker daemon**. The
first run pulls both images and is slow.

```bash
mvn test
```

### Windows + Docker inside WSL2

If Docker runs as a native daemon inside a WSL2 distro rather than as Docker
Desktop, the Windows JVM cannot see it by default. Two things are needed.

**1. Keep the distro alive for the whole run.** WSL2 stops a distro as soon as
its last command exits, which takes the daemon down mid-run and surfaces as
`DOCKER_HOST ... is not listening` or `Connection refused`. In a terminal of its
own:

```bash
wsl -d Ubuntu -- bash -c 'sleep 5400'
```

**2. Point Testcontainers at the daemon over TCP.** A one-off `socat` bridge
exposes the daemon's unix socket on port 2375 (`--restart unless-stopped`, so it
comes back with the distro):

```bash
wsl -d Ubuntu -- docker run -d --name docker-tcp-proxy --restart unless-stopped --network host -v /var/run/docker.sock:/var/run/docker.sock alpine/socat TCP-LISTEN:2375,fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock
```

Find the distro's address with `wsl -d Ubuntu -- hostname -I` and use the **first
entry**. Use the literal IPv4 address, not `localhost` — the JVM may resolve
`localhost` to `::1` and report the port as closed.

```bash
DOCKER_HOST=tcp://172.18.63.240:2375 mvn test
```

The address is assigned by WSL and can change when the distro restarts, so
re-read it rather than hard-coding it anywhere.

### Why the pom has a `windows-unixdomain-tmpdir` profile

On some Windows setups `java.nio.channels.Selector.open()` fails in *every* JVM
with `IOException: Unable to establish loopback connection`. The JDK backs the
selector's wakeup pipe with an AF_UNIX socket created in `%TEMP%`; where security
software holds that folder, the socket binds but cannot be connected to. That
breaks anything selector-based — the JDK `HttpServer` webhook stubs in the tests,
Kafka clients, Testcontainers.

The Windows-only profile in [`pom.xml`](pom.xml) redirects the JDK's AF_UNIX
scratch directory to `target/`:

```
-Djdk.net.unixdomain.tmpdir=${project.build.directory}
```

Setting `-Djava.io.tmpdir` does **not** help — `UnixDomainSockets` reads the
`TEMP` environment variable, not that property. Fixing `TMP`/`TEMP` at the OS
level fixes it machine-wide. Linux and CI runs keep the platform default.

### Narrowing a run

```bash
mvn test -Dtest=EventProcessingFlowIntTest
```

```bash
mvn test -Dtest=NotificationEventTest#shouldRejectInvalidTransition
```

---

## Configuration

All values live in [`src/main/resources/application.yaml`](src/main/resources/application.yaml).

| Property | Default | Meaning |
|---|---|---|
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

All three carry a `version` column for optimistic locking.

---

## Project layout

```
src/main/java/com/fardorado/notification/
  domain/        model + state machine; no Spring, JPA, Kafka or Jackson
  application/   use cases, commands, ports (in/out), domain services
  adapter/in/    messaging (Kafka consumer), scheduler
  adapter/out/   persistence (JPA entities, repositories, mappers), webhook client
  configuration/ properties, worker pool, scheduling
```

Dependencies point inward: `adapter → application → domain`.

---

## Documentation

- `docs/spec/` — requirements and C4 design
- `docs/spec/features/` — per-feature specifications
- `docs/ai/rules/` — enforced architecture, naming and project conventions
- `AGENTS.md` — orientation for coding agents

---

## Notes for contributors

- **Jackson 3.** Spring Boot 4 auto-configures `tools.jackson.databind.json.JsonMapper`.
  Jackson 2 (`com.fasterxml.jackson.databind`) is only a transitive dependency and
  has no bean — injecting `ObjectMapper` from it will compile and then fail at
  startup. Annotations stay in `com.fasterxml.jackson.annotation`.
- **Lombok and MapStruct** are wired through `annotationProcessorPaths` in
  *both* the `default-compile` and `default-testCompile` executions of
  `maven-compiler-plugin`. Adding a processor means editing both.
- **`TestRestTemplate` moved in Boot 4** to `org.springframework.boot.resttestclient`.
- Integration tests run the full Spring context against Testcontainers
  (`RANDOM_PORT`, `TestRestTemplate`), never MockMvc.
