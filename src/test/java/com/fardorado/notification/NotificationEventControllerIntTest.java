package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

/**
 * The notification-events REST API against the full Spring context, a real
 * PostgreSQL and real HTTP.
 *
 * <p>The dispatch scheduler is disabled so that an event replayed back to
 * {@code PENDING} is not immediately claimed and moved on by a background
 * sweep while the assertions run.</p>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "notification.processing.dispatch-enabled=false")
@AutoConfigureTestRestTemplate
class NotificationEventControllerIntTest {

    private static final String CLIENT_A = "CLIENT-API-A";
    private static final String CLIENT_B = "CLIENT-API-B";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private NotificationEventRepository notificationEventRepository;
    @Autowired private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long subscriptionA;
    private Long subscriptionB;

    @BeforeEach
    void seedSubscriptions() {
        subscriptionA = subscriptionOf(CLIENT_A, EventType.CREDIT_CARD_PAYMENT);
        subscriptionB = subscriptionOf(CLIENT_B, EventType.CREDIT_CARD_PAYMENT);
    }

    // ----------------------------------------------------------------- list

    @Test
    void shouldListOnlyTheCallingClientsEvents() {
        persistEvent("API-L1", subscriptionA, NotificationEventStatus.FAILED);
        persistEvent("API-L2", subscriptionB, NotificationEventStatus.FAILED);

        var response = get("/notification_events?size=100", CLIENT_A, PagedBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).contains("API-L1").doesNotContain("API-L2");
    }

    @Test
    void shouldReturnPaginationMetadata() {
        persistEvent("API-P1", subscriptionA, NotificationEventStatus.FAILED);

        var response = get("/notification_events?page=0&size=1", CLIENT_A, PagedBody.class);

        PagedBody body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.items()).hasSize(1);
        assertThat(body.totalElements()).isPositive();
        assertThat(body.totalPages()).isEqualTo((int) Math.ceil((double) body.totalElements() / 1));
    }

    @Test
    void shouldExposeAttemptCountAndLastHttpStatus() {
        Long eventId = persistEvent("API-L3", subscriptionA, NotificationEventStatus.FAILED);
        failAttempt(eventId, 1, 500);
        failAttempt(eventId, 2, 503);

        var response = get("/notification_events?size=100", CLIENT_A, PagedBody.class);

        SummaryBody summary = summaryOf(response.getBody(), "API-L3");
        assertThat(summary.attemptCount()).isEqualTo(2);
        assertThat(summary.lastHttpStatus()).isEqualTo(503);
        assertThat(summary.eventType()).isEqualTo("CREDIT_CARD_PAYMENT");
        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.createdAt()).isNotNull();
    }

    @Test
    void shouldFilterByDeliveryStatus() {
        persistEvent("API-F-FAILED", subscriptionA, NotificationEventStatus.FAILED);
        persistEvent("API-F-PENDING", subscriptionA, NotificationEventStatus.PENDING);

        var response = get("/notification_events?delivery_status=FAILED&size=100", CLIENT_A, PagedBody.class);

        assertThat(ids(response.getBody()))
                .contains("API-F-FAILED")
                .doesNotContain("API-F-PENDING");
    }

    @Test
    void shouldFilterByCreationDateRange() {
        persistEvent("API-D1", subscriptionA, NotificationEventStatus.FAILED);

        var inRange = get("/notification_events?from=2000-01-01T00:00:00Z&size=100", CLIENT_A, PagedBody.class);
        assertThat(ids(inRange.getBody())).contains("API-D1");

        var outOfRange = get("/notification_events?to=2000-01-01T00:00:00Z&size=100", CLIENT_A, PagedBody.class);
        assertThat(ids(outOfRange.getBody())).doesNotContain("API-D1");
    }

    // --------------------------------------------------------------- detail

    @Test
    void shouldReturnEventDetailsWithAttemptHistory() {
        Long eventId = persistEvent("API-D-OK", subscriptionA, NotificationEventStatus.FAILED);
        failAttempt(eventId, 1, 500);
        failAttempt(eventId, 2, 503);

        var response = get("/notification_events/API-D-OK", CLIENT_A, DetailBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DetailBody body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo("API-D-OK");
        assertThat(body.status()).isEqualTo("FAILED");
        assertThat(body.attemptCount()).isEqualTo(2);
        assertThat(body.lastHttpStatus()).isEqualTo(503);
        assertThat(body.webhookUrl()).isEqualTo("https://example.com/" + CLIENT_A);
        assertThat(body.attempts()).extracting(AttemptBody::attempt).containsExactly(1, 2);
        assertThat(body.attempts()).extracting(AttemptBody::httpStatus).containsExactly(500, 503);
    }

    @Test
    void shouldNotRevealAnotherClientsEvent() {
        persistEvent("API-D-THEIRS", subscriptionB, NotificationEventStatus.FAILED);

        var response = get("/notification_events/API-D-THEIRS", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldReturnNotFoundForUnknownEvent() {
        var response = get("/notification_events/API-DOES-NOT-EXIST", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    // --------------------------------------------------------------- replay

    @Test
    void shouldAcceptReplayOfFailedEventAndRequeueIt() {
        persistEvent("API-R-FAILED", subscriptionA, NotificationEventStatus.FAILED);

        var response = post("/notification_events/API-R-FAILED/replay", CLIENT_A, ReplayBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().notificationEventId()).isEqualTo("API-R-FAILED");
        assertThat(response.getBody().status()).isEqualTo("PENDING");
        assertThat(statusOf("API-R-FAILED")).isEqualTo("PENDING");
    }

    @Test
    void shouldRejectReplayOfCompletedEvent() {
        persistEvent("API-R-COMPLETED", subscriptionA, NotificationEventStatus.COMPLETED);

        var response = post("/notification_events/API-R-COMPLETED/replay", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("NOTIFICATION_EVENT_NOT_REPLAYABLE");
        assertThat(statusOf("API-R-COMPLETED")).isEqualTo("COMPLETED");
    }

    @Test
    void shouldNotReplayAnotherClientsEvent() {
        persistEvent("API-R-THEIRS", subscriptionB, NotificationEventStatus.FAILED);

        var response = post("/notification_events/API-R-THEIRS/replay", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf("API-R-THEIRS")).isEqualTo("FAILED");
    }

    /**
     * Two simultaneous replays of the same event must not both take effect:
     * the optimistic-lock loser is reported as a conflict, so the event is
     * never queued for replay twice.
     */
    @Test
    void shouldAcceptOnlyOneOfTwoConcurrentReplays() throws Exception {
        persistEvent("API-R-RACE", subscriptionA, NotificationEventStatus.FAILED);

        Callable<ResponseEntity<String>> replay =
                () -> post("/notification_events/API-R-RACE/replay", CLIENT_A, String.class);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = executor.submit(replay);
            Future<ResponseEntity<String>> second = executor.submit(replay);

            List<HttpStatus> statuses = List.of(
                    (HttpStatus) first.get(30, TimeUnit.SECONDS).getStatusCode(),
                    (HttpStatus) second.get(30, TimeUnit.SECONDS).getStatusCode());

            assertThat(statuses).containsOnlyOnce(HttpStatus.ACCEPTED);
            assertThat(statuses).filteredOn(status -> status == HttpStatus.ACCEPTED).hasSize(1);
            assertThat(statuses).allMatch(
                    status -> status == HttpStatus.ACCEPTED || status == HttpStatus.CONFLICT);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusOf("API-R-RACE")).isEqualTo("PENDING");
        // The replay only re-queues the event; it must not fabricate attempts.
        assertThat(attemptCountOf("API-R-RACE")).isZero();
    }

    // --------------------------------------------------------------- errors

    @Test
    void shouldRejectRequestWithoutClientHeader() {
        var response = restTemplate.getForEntity("/notification_events", ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void shouldRejectNegativePage() {
        var response = get("/notification_events?page=-1", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().errors()).extracting(FieldBody::field).contains("page");
    }

    @Test
    void shouldRejectZeroSize() {
        var response = get("/notification_events?size=0", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldRejectSizeAboveConfiguredMaximum() {
        var response = get("/notification_events?size=101", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void shouldRejectInvertedDateRange() {
        var response = get(
                "/notification_events?from=2026-10-05T00:00:00Z&to=2026-10-01T00:00:00Z",
                CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void shouldRejectUnknownDeliveryStatus() {
        var response = get("/notification_events?delivery_status=NOPE", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldRejectMalformedDate() {
        var response = get("/notification_events?from=not-a-date", CLIENT_A, ErrorBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldReturnAWellFormedErrorBodyWithoutInternalDetail() {
        var response = get("/notification_events/API-DOES-NOT-EXIST", CLIENT_A, String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"code\":\"RESOURCE_NOT_FOUND\"", "\"path\":", "\"datetime\":",
                "\"correlationId\":");
        // The resource path legitimately contains the table-like segment
        // "notification_events", so the leak check targets internals only.
        assertThat(body)
                .doesNotContain("com.fardorado")
                .doesNotContain("Exception")
                .doesNotContain("at org.springframework")
                .doesNotContain("select ")
                .doesNotContain("NotificationEventEntity");
    }

    @Test
    void shouldEchoTheSuppliedCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", CLIENT_A);
        headers.set("X-Correlation-Id", "corr-api-1");

        var response = restTemplate.exchange(
                "/notification_events", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-api-1");
    }

    @Test
    void shouldGenerateACorrelationIdWhenNoneIsSupplied() {
        var response = get("/notification_events", CLIENT_A, String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    // --------------------------------------------------------------- openapi

    @Test
    void shouldDocumentEveryEndpointInTheOpenApiContract() {
        var response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String document = response.getBody();
        assertThat(document).isNotNull();
        assertThat(document).contains(
                "\"/notification_events\"",
                "\"/notification_events/{notification_event_id}\"",
                "\"/notification_events/{notification_event_id}/replay\"");
        // Documented on the interface: summaries, the error schema and the
        // status codes the contract promises.
        assertThat(document).contains("List notification events", "Get notification event details",
                "Replay a failed notification event");
        assertThat(document).contains("ErrorResponseDto");
        assertThat(document).contains("\"202\"", "\"404\"", "\"409\"");
    }

    // ---------------------------------------------------------------- setup

    private <T> ResponseEntity<T> get(String path, String clientId, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, entityFor(clientId), responseType);
    }

    private <T> ResponseEntity<T> post(String path, String clientId, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, entityFor(clientId), responseType);
    }

    private HttpEntity<Void> entityFor(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", clientId);
        return new HttpEntity<>(headers);
    }

    private Long subscriptionOf(String clientId, EventType eventType) {
        return subscriptionRepository
                .findByClientIdAndEventTypeAndStatus(clientId, eventType, SubscriptionStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElseGet(() -> subscriptionRepository.save(Subscription.newSubscription(
                        clientId, eventType, "https://example.com/" + clientId)))
                .getId();
    }

    /**
     * Seeds an event directly in the target status. Transitions go through
     * the domain so no illegal state can be written.
     */
    private Long persistEvent(String eventId, Long subscriptionId, NotificationEventStatus status) {
        NotificationEvent event = NotificationEvent.newEvent(
                eventId, EventType.CREDIT_CARD_PAYMENT, 1, "corr-" + eventId,
                "{\"content\": \"api test\"}", subscriptionId);
        switch (status) {
            case PENDING -> { }
            case DELIVERING -> event.markDelivering();
            case RETRY_SCHEDULED -> {
                event.markDelivering();
                event.scheduleRetry(Instant.now().plusSeconds(3600));
            }
            case COMPLETED -> {
                event.markDelivering();
                event.markCompleted();
            }
            case FAILED -> {
                event.markDelivering();
                event.markFailed();
            }
        }
        return notificationEventRepository.save(event).getId();
    }

    private void failAttempt(Long notificationEventId, int attemptNumber, Integer httpStatus) {
        DeliveryAttempt attempt = DeliveryAttempt.startAttempt(notificationEventId, attemptNumber);
        attempt = deliveryAttemptRepository.save(attempt);
        attempt.markFailed("webhook endpoint returned HTTP %s".formatted(httpStatus), httpStatus,
                Instant.now());
        deliveryAttemptRepository.save(attempt);
    }

    private String statusOf(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_event WHERE event_id = ?", String.class, eventId);
    }

    private int attemptCountOf(String eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM delivery_attempt da
                  JOIN notification_event ne ON da.notification_event_id = ne.id
                 WHERE ne.event_id = ?
                """,
                Integer.class, eventId);
    }

    private List<String> ids(PagedBody body) {
        assertThat(body).isNotNull();
        return body.items().stream().map(SummaryBody::id).toList();
    }

    private SummaryBody summaryOf(PagedBody body, String id) {
        assertThat(body).isNotNull();
        return body.items().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary for " + id));
    }

    // Deserialization views of the API contract, kept separate from the
    // production DTOs so a change to those shows up here as a test failure.

    record PagedBody(List<SummaryBody> items, int page, int size, long totalElements, int totalPages) {
    }

    record SummaryBody(String id, String eventType, Instant createdAt, String status,
                       int attemptCount, Integer lastHttpStatus) {
    }

    record DetailBody(String id, String eventType, Instant createdAt, String status,
                      int attemptCount, Integer lastHttpStatus, String webhookUrl,
                      List<AttemptBody> attempts) {
    }

    record AttemptBody(int attempt, String status, Integer httpStatus) {
    }

    record ReplayBody(String notificationEventId, String status) {
    }

    record ErrorBody(String code, String message, String description, String path,
                     String datetime, String correlationId, List<FieldBody> errors) {
    }

    record FieldBody(String field, String message) {
    }
}
