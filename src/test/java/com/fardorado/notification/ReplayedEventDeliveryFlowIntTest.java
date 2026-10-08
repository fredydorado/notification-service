package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Replay followed by delivery, end to end: an event ingested from Kafka
 * exhausts its retries against a broken webhook and ends {@code FAILED}; the
 * client then replays it through the REST API once the endpoint has recovered,
 * and the running dispatcher delivers it successfully.
 *
 * <p>Unlike {@code NotificationEventControllerIntTest}, the dispatch scheduler
 * is <em>enabled</em> here: the point is that the replayed {@code PENDING}
 * event is picked up by the real processor and not just re-queued. The test
 * therefore never asserts that the event <em>stays</em> {@code PENDING} after
 * the replay call, as the dispatcher may already have claimed it.</p>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Own topic: the consumer group is shared across cached Spring
        // contexts, so each Kafka IntTest class needs a topic of its own.
        "notification.kafka.topic=notification-events-replay-test",
        "notification.processing.poll-interval=200ms",
        "notification.processing.max-attempts=2",
        "notification.processing.backoff-initial=100ms",
        "notification.processing.backoff-max=200ms",
        "notification.processing.lease-timeout=2s"
})
@AutoConfigureTestRestTemplate
class ReplayedEventDeliveryFlowIntTest {

    private static final String TOPIC = "notification-events-replay-test";
    private static final String CLIENT = "CLIENT-REPLAY-1";
    private static final String OTHER_CLIENT = "CLIENT-REPLAY-2";

    private static HttpServer webhookStub;
    private static String stubBaseUrl;

    /** While {@code false} the stub answers HTTP 500; once flipped it answers 200. */
    private static final AtomicBoolean ENDPOINT_HEALTHY = new AtomicBoolean(false);
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SubscriptionRepository subscriptionRepository;

    @BeforeAll
    static void startWebhookStub() throws IOException {
        webhookStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhookStub.createContext("/recovering", exchange -> {
            REQUEST_COUNT.incrementAndGet();
            LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, ENDPOINT_HEALTHY.get() ? 200 : 500);
        });
        webhookStub.start();
        stubBaseUrl = "http://127.0.0.1:" + webhookStub.getAddress().getPort();
    }

    @AfterAll
    static void stopWebhookStub() {
        webhookStub.stop(0);
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void shouldDeliverReplayedFailedEventOnceTheEndpointHasRecovered() {
        seedSubscription(CLIENT, EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/recovering");
        // The flag is shared with the other test, so never rely on its initial value.
        ENDPOINT_HEALTHY.set(false);

        // 1. The webhook is broken, so ingestion + dispatch exhaust the
        //    retries (max-attempts=2) and the event ends up FAILED.
        publish("EVT-REPLAY-1", CLIENT, "replayed payment content");
        awaitEventStatus("EVT-REPLAY-1", "FAILED");
        assertThat(attemptsOf("EVT-REPLAY-1"))
                .extracting(attempt -> attempt.get("status"))
                .containsExactly("FAILED", "FAILED");
        int requestsBeforeReplay = REQUEST_COUNT.get();

        // 2. The client's endpoint is fixed and the client replays the event.
        ENDPOINT_HEALTHY.set(true);
        var replay = restTemplate.exchange("/notification_events/EVT-REPLAY-1/replay",
                HttpMethod.POST, entityFor(CLIENT), ReplayBody.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().notificationEventId()).isEqualTo("EVT-REPLAY-1");
        assertThat(replay.getBody().status()).isEqualTo("PENDING");

        // 3. The dispatcher claims the PENDING event and delivers it.
        awaitEventStatus("EVT-REPLAY-1", "COMPLETED");

        // The replay appended a new attempt; the failed history is untouched.
        // Attempt numbers continue from the existing history instead of
        // restarting at 1.
        List<Map<String, Object>> attempts = attemptsOf("EVT-REPLAY-1");
        assertThat(attempts)
                .extracting(attempt -> attempt.get("attempt_number"))
                .containsExactly(1, 2, 3);
        assertThat(attempts)
                .extracting(attempt -> attempt.get("status"))
                .containsExactly("FAILED", "FAILED", "SUCCESS");
        assertThat(attempts.getLast().get("http_status")).isEqualTo(200);

        // Exactly one extra webhook call was made by the replay, carrying the
        // original event envelope.
        assertThat(REQUEST_COUNT.get()).isEqualTo(requestsBeforeReplay + 1);
        assertThat(LAST_BODY.get()).contains("\"event_id\":\"EVT-REPLAY-1\"");
        assertThat(LAST_BODY.get()).contains("replayed payment content");

        // The event is now COMPLETED, so it is no longer replayable.
        var secondReplay = restTemplate.exchange("/notification_events/EVT-REPLAY-1/replay",
                HttpMethod.POST, entityFor(CLIENT), String.class);
        assertThat(secondReplay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldNotRequeueOrDeliverAnotherClientsFailedEvent() {
        seedSubscription(OTHER_CLIENT, EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/recovering");
        ENDPOINT_HEALTHY.set(false);

        publish("EVT-REPLAY-2", OTHER_CLIENT, "someone else's payment content");
        awaitEventStatus("EVT-REPLAY-2", "FAILED");
        int requestsBeforeReplay = REQUEST_COUNT.get();
        ENDPOINT_HEALTHY.set(true);

        // CLIENT is not the owner: the event is invisible to it, so the
        // replay is a 404 and nothing is re-queued.
        var response = restTemplate.exchange("/notification_events/EVT-REPLAY-2/replay",
                HttpMethod.POST, entityFor(CLIENT), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Several dispatcher sweeps (poll-interval=200ms) pass without the
        // event being picked up again.
        await().during(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(statusOf("EVT-REPLAY-2")).isEqualTo("FAILED");
            assertThat(attemptsOf("EVT-REPLAY-2")).hasSize(2);
            assertThat(REQUEST_COUNT.get()).isEqualTo(requestsBeforeReplay);
        });
    }

    private void seedSubscription(String clientId, EventType eventType, String webhookUrl) {
        if (subscriptionRepository
                .findByClientIdAndEventTypeAndStatus(clientId, eventType, SubscriptionStatus.ACTIVE)
                .isEmpty()) {
            subscriptionRepository.save(Subscription.newSubscription(clientId, eventType, webhookUrl));
        }
    }

    private void publish(String eventId, String clientId, String content) {
        String message = JsonMapper.builder().build().createObjectNode()
                .put("event_id", eventId)
                .put("event_type", "credit_card_payment")
                .put("event_version", 1)
                .put("correlation_id", "corr-" + eventId)
                .put("content", content)
                .put("client_id", clientId)
                .toString();
        kafkaTemplate.send(TOPIC, eventId, message);
    }

    private HttpEntity<Void> entityFor(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", clientId);
        return new HttpEntity<>(headers);
    }

    private void awaitEventStatus(String eventId, String status) {
        // The row does not exist until Kafka ingestion has committed, so the
        // lookup must produce a retryable assertion failure rather than an
        // EmptyResultDataAccessException that would abort the await.
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForList(
                        "SELECT status FROM notification_event WHERE event_id = ?",
                        String.class, eventId))
                        .containsExactly(status));
    }

    private String statusOf(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_event WHERE event_id = ?", String.class, eventId);
    }

    private List<Map<String, Object>> attemptsOf(String eventId) {
        return jdbcTemplate.queryForList(
                """
                SELECT da.attempt_number, da.status, da.error_message, da.http_status
                  FROM delivery_attempt da
                  JOIN notification_event ne ON da.notification_event_id = ne.id
                 WHERE ne.event_id = ?
                 ORDER BY da.attempt_number
                """,
                eventId);
    }

    /** Deserialization view of the replay response, independent of the production DTO. */
    record ReplayBody(String notificationEventId, String status) {
    }
}
