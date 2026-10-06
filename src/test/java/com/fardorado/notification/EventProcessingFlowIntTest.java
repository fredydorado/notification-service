package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

/**
 * End-to-end event processing flow: Kafka ingestion, durable persistence,
 * subscription matching, dispatch, webhook delivery, retry/backoff, permanent
 * failure and stale-delivery recovery — against real Kafka, PostgreSQL and a
 * local stub webhook server.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notification.kafka.topic=notification-events-flow-test",
        "notification.processing.poll-interval=200ms",
        "notification.processing.max-attempts=2",
        "notification.processing.backoff-initial=100ms",
        "notification.processing.backoff-max=200ms",
        "notification.processing.lease-timeout=2s"
})
class EventProcessingFlowIntTest {

    private static final String TOPIC = "notification-events-flow-test";

    private static HttpServer webhookStub;
    private static String stubBaseUrl;
    private static final Map<String, AtomicInteger> REQUEST_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_BODIES = new ConcurrentHashMap<>();

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private NotificationEventRepository notificationEventRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @BeforeAll
    static void startWebhookStub() throws IOException {
        webhookStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub(webhookStub, "/success", 200);
        stub(webhookStub, "/flaky", 500, 200);
        stub(webhookStub, "/always-fail", 500);
        stub(webhookStub, "/not-found", 404);
        webhookStub.start();
        stubBaseUrl = "http://127.0.0.1:" + webhookStub.getAddress().getPort();
    }

    @AfterAll
    static void stopWebhookStub() {
        webhookStub.stop(0);
    }

    private static void stub(HttpServer server, String path, int... statusesInOrder) {
        AtomicInteger counter = new AtomicInteger();
        REQUEST_COUNTS.put(path, counter);
        server.createContext(path, exchange -> {
            counter.incrementAndGet();
            LAST_BODIES.put(path, new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            int status = statusesInOrder[Math.min(counter.get() - 1, statusesInOrder.length - 1)];
            respond(exchange, status);
        });
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void shouldCompleteSuccessfulDelivery() {
        seedSubscription("CLIENT-F1", EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/success");

        publish("EVT-FLOW-1", "CLIENT-F1", "successful payment content");

        awaitEventStatus("EVT-FLOW-1", "COMPLETED");

        List<Map<String, Object>> attempts = attemptsOf("EVT-FLOW-1");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().get("status")).isEqualTo("SUCCESS");
        assertThat(attempts.getFirst().get("attempt_number")).isEqualTo(1);
        // The webhook's HTTP status is captured on the attempt.
        assertThat(attempts.getFirst().get("http_status")).isEqualTo(200);

        // The delivered payload carries the event envelope with the event id
        // and content.
        assertThat(LAST_BODIES.get("/success")).contains("\"event_id\":\"EVT-FLOW-1\"");
        assertThat(LAST_BODIES.get("/success")).contains("successful payment content");
    }

    @Test
    void shouldRetryTransientFailureAndAppendNewAttempt() {
        // /flaky fails the first request with HTTP 500, then succeeds.
        seedSubscription("CLIENT-F2", EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/flaky");

        publish("EVT-FLOW-2", "CLIENT-F2", "flaky payment content");

        awaitEventStatus("EVT-FLOW-2", "COMPLETED");

        List<Map<String, Object>> attempts = attemptsOf("EVT-FLOW-2");
        assertThat(attempts).hasSize(2);
        // Retries append new delivery-attempt records; previous attempts are
        // never overwritten.
        assertThat(attempts)
                .extracting(attempt -> attempt.get("attempt_number"))
                .containsExactly(1, 2);
        assertThat(attempts)
                .extracting(attempt -> attempt.get("status"))
                .containsExactly("FAILED", "SUCCESS");
    }

    @Test
    void shouldFailPermanentlyWhenRetriesAreExhausted() {
        // /always-fail keeps failing with HTTP 500; max-attempts is 2.
        seedSubscription("CLIENT-F3", EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/always-fail");

        publish("EVT-FLOW-3", "CLIENT-F3", "doomed payment content");

        awaitEventStatus("EVT-FLOW-3", "FAILED");

        List<Map<String, Object>> attempts = attemptsOf("EVT-FLOW-3");
        assertThat(attempts).hasSize(2);
        assertThat(attempts)
                .extracting(attempt -> attempt.get("status"))
                .containsExactly("FAILED", "FAILED");
    }

    @Test
    void shouldFailPermanentlyOn4xxWithoutRetry() {
        // /not-found responds 404: permanent failure, no retry.
        seedSubscription("CLIENT-F4", EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/not-found");

        publish("EVT-FLOW-4", "CLIENT-F4", "rejected payment content");

        awaitEventStatus("EVT-FLOW-4", "FAILED");

        List<Map<String, Object>> attempts = attemptsOf("EVT-FLOW-4");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().get("status")).isEqualTo("FAILED");
        assertThat((String) attempts.getFirst().get("error_message")).contains("HTTP 404");
        assertThat(attempts.getFirst().get("http_status")).isEqualTo(404);
    }

    @Test
    void shouldRecoverStaleDeliveringEvent() {
        seedSubscription("CLIENT-F5", EventType.CREDIT_CARD_PAYMENT, stubBaseUrl + "/success");

        // Simulates a crash during delivery: the event is DELIVERING with an
        // IN_PROGRESS attempt, and its last update is older than the lease.
        Subscription subscription = subscriptionRepository
                .findByClientIdAndEventTypeAndStatus(
                        "CLIENT-F5", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE)
                .getFirst();
        NotificationEvent stale = NotificationEvent.newEvent(
                "EVT-FLOW-STALE", EventType.CREDIT_CARD_PAYMENT, 1, null,
                // The payload column is JSONB: plain-text content reaches the
                // database already wrapped by the consumer, so the simulated
                // crashed event must be seeded the same way.
                "{\"content\": \"stale payment content\"}", subscription.getId());
        stale.markDelivering();
        stale = notificationEventRepository.save(stale);
        DeliveryAttempt inProgress = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(stale.getId(), 1));
        jdbcTemplate.update(
                "UPDATE notification_event SET updated_at = now() - interval '1 hour' WHERE id = ?",
                stale.getId());

        awaitEventStatus("EVT-FLOW-STALE", "COMPLETED");

        // The stale IN_PROGRESS attempt is failed, the retry appends a new
        // successful attempt; the event is never silently completed.
        List<Map<String, Object>> attempts = attemptsOf("EVT-FLOW-STALE");
        assertThat(attempts).hasSize(2);
        assertThat(attempts)
                .extracting(attempt -> attempt.get("status"))
                .containsExactly("FAILED", "SUCCESS");
        assertThat((String) attempts.getFirst().get("error_message"))
                .contains("stale delivery recovered");
        // No response was ever received for the recovered attempt, but the
        // retry that followed it reached the endpoint.
        assertThat(attempts.getFirst().get("http_status")).isNull();
        assertThat(attempts.getLast().get("http_status")).isEqualTo(200);
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
}
