package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.Subscription;

/**
 * Verifies event ingestion through a real Kafka broker and PostgreSQL:
 *
 * <ul>
 *   <li>the sample events from {@code notification_events.json} are consumed,
 *       durably persisted and linked to the matching active subscription,</li>
 *   <li>{@code event_id == notification_event_id} identity is preserved,</li>
 *   <li>repeated delivery of the same {@code event_id} creates no duplicate
 *       processing (durable idempotency via the database unique
 *       constraint),</li>
 *   <li>poison messages (malformed / invalid events) do not block the
 *       partition.</li>
 * </ul>
 *
 * <p>Dispatching is disabled in this test so the assertion target is exactly
 * the durable ingestion state ({@code PENDING}).</p>
 */
@Import({TestcontainersConfiguration.class, KafkaNotificationConsumerIntTest.SampleEventsConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "notification.processing.dispatch-enabled=false")
class KafkaNotificationConsumerIntTest {

    private static final String TOPIC = "notification-events-ingestion-test";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SampleEventsConfiguration sampleEvents;

    @DynamicPropertySource
    static void kafkaTopic(DynamicPropertyRegistry registry) {
        registry.add("notification.kafka.topic", () -> TOPIC);
    }

    @BeforeAll
    static void seedSubscriptions(
            @Autowired SubscriptionRepository subscriptionRepository,
            @Autowired SampleEventsConfiguration sampleEvents) {
        for (JsonNode event : sampleEvents.sampleEvents()) {
            String clientId = event.get("client_id").asText();
            String eventType = event.get("event_type").asText();
            if (subscriptionRepository
                    .findByClientIdAndEventTypeAndStatus(clientId, toEventType(eventType),
                            com.fardorado.notification.domain.model.subscription.SubscriptionStatus.ACTIVE)
                    .isEmpty()) {
                subscriptionRepository.save(Subscription.newSubscription(
                        clientId, toEventType(eventType), "https://example.com/webhook"));
            }
        }
    }

    @Test
    void shouldConsumeSampleEventsAndPersistThemDurably() {
        for (JsonNode event : sampleEvents.sampleEvents()) {
            kafkaTemplate.send(TOPIC, event.get("event_id").asText(), event.toString());
        }

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotificationEvents()).isEqualTo(sampleEvents.sampleEvents().size()));

        for (JsonNode event : sampleEvents.sampleEvents()) {
            String eventId = event.get("event_id").asText();
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT event_id, event_type, event_version, status, payload, subscription_id"
                            + " FROM notification_event WHERE event_id = ?",
                    eventId);
            assertThat(row.get("event_id")).isEqualTo(eventId);
            assertThat(row.get("event_type")).isEqualTo(event.get("event_type").asText());
            assertThat(((Number) row.get("event_version")).intValue()).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("PENDING");
            // Plain-text content is wrapped as {"content": "..."} because the
            // payload column is JSONB.
            assertThat(row.get("payload").toString())
                    .contains(event.get("content").asText());

            Long subscriptionId = ((Number) row.get("subscription_id")).longValue();
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
            assertThat(subscription.getClientId()).isEqualTo(event.get("client_id").asText());
            assertThat(subscription.getEventType()).isEqualTo(toEventType(event.get("event_type").asText()));
        }
    }

    @Test
    void shouldNotDuplicateProcessingWhenSameEventsAreDeliveredAgain() {
        for (JsonNode event : sampleEvents.sampleEvents()) {
            kafkaTemplate.send(TOPIC, event.get("event_id").asText(), event.toString());
        }
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotificationEvents()).isEqualTo(sampleEvents.sampleEvents().size()));

        // The broker may redeliver the same events (e.g. rebalance); repeated
        // event_id delivery must not create duplicate processing.
        for (JsonNode event : sampleEvents.sampleEvents()) {
            kafkaTemplate.send(TOPIC, event.get("event_id").asText(), event.toString());
        }
        await().during(3, TimeUnit.SECONDS).atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotificationEvents()).isEqualTo(sampleEvents.sampleEvents().size()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_event", Integer.class))
                .isEqualTo(sampleEvents.sampleEvents().size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT event_id) FROM notification_event", Integer.class))
                .isEqualTo(sampleEvents.sampleEvents().size());
    }

    @Test
    void shouldAcknowledgePoisonMessagesWithoutBlockingThePartition() {
        kafkaTemplate.send(TOPIC, "poison-1", "{ not valid json");
        kafkaTemplate.send(TOPIC, "poison-2", """
                {"event_id": "POISON002", "event_type": "unknown_event_type",
                 "content": "x", "client_id": "CLIENT001"}
                """);
        kafkaTemplate.send(TOPIC, "poison-3", """
                {"event_id": "POISON003", "event_type": "credit_card_payment",
                 "content": "x"}
                """);

        kafkaTemplate.send(TOPIC, "valid-after-poison", """
                {"event_id": "EVT-VALID-AFTER-POISON", "event_type": "credit_card_payment",
                 "event_version": 2, "correlation_id": "corr-99",
                 "content": "valid event after poison messages", "client_id": "CLIENT001"}
                """);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT event_id, event_version, correlation_id FROM notification_event"
                            + " WHERE event_id = 'EVT-VALID-AFTER-POISON'");
            assertThat(((Number) row.get("event_version")).intValue()).isEqualTo(2);
            assertThat(row.get("correlation_id")).isEqualTo("corr-99");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_event WHERE event_id IN ('POISON002', 'POISON003')",
                Integer.class))
                .isZero();
    }

    private Integer countNotificationEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
    }

    private static EventType toEventType(String eventType) {
        return EventType.valueOf(eventType.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Loads the sample events (verbatim copy of the broker's sample
     * {@code notification_events.json}) and exposes them as a test bean.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SampleEventsConfiguration {

        private final ObjectMapper objectMapper = new ObjectMapper();

        List<JsonNode> sampleEvents() {
            try {
                JsonNode root = objectMapper.readTree(
                        getClass().getResourceAsStream("/sample/notification_events.json"));
                return objectMapper.convertValue(root.get("events"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
            } catch (Exception e) {
                throw new IllegalStateException("cannot load sample events", e);
            }
        }
    }
}
