package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "notification.processing.dispatch-enabled=false")
class NotificationEventPersistenceIntTest {

    @Autowired
    private NotificationEventRepository notificationEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSaveAndFindEventWithJsonbPayload() {
        NotificationEvent saved = notificationEventRepository.save(NotificationEvent.newEvent(
                "evt-1", EventType.CREDIT_CARD_PAYMENT, 1, "corr-1", "{\"card_last_four\": \"1234\"}", null));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0);

        NotificationEvent loaded = notificationEventRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getEventId()).isEqualTo("evt-1");
        assertThat(loaded.getEventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(loaded.getEventVersion()).isEqualTo(1);
        assertThat(loaded.getCorrelationId()).isEqualTo("corr-1");
        assertThat(loaded.getStatus()).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(loaded.getPayload()).isEqualTo("{\"card_last_four\": \"1234\"}");

        NotificationEvent loadedByEventId = notificationEventRepository.findByEventId("evt-1").orElseThrow();
        assertThat(loadedByEventId.getId()).isEqualTo(saved.getId());

        String payloadType = jdbcTemplate.queryForObject(
                "SELECT pg_typeof(payload)::text FROM notification_event WHERE id = ?",
                String.class,
                saved.getId());
        assertThat(payloadType).isEqualTo("jsonb");
    }

    @Test
    void shouldRejectDuplicateEventId() {
        notificationEventRepository.save(
                NotificationEvent.newEvent("evt-dup", EventType.CREDIT_TRANSFER, 1, null, "{}", null));

        assertThatThrownBy(() -> notificationEventRepository.save(
                        NotificationEvent.newEvent("evt-dup", EventType.CREDIT_TRANSFER, 1, null, "{}", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldClaimPendingAndDueRetryEventsOldestFirst() throws InterruptedException {
        NotificationEvent pending =
                notificationEventRepository.save(NotificationEvent.newEvent("evt-p", EventType.CREDIT_TRANSFER, 1, null, "{}", null));
        NotificationEvent oldestRetryable = persistDueRetryScheduledEvent("evt-r1");
        NotificationEvent newestRetryable = persistDueRetryScheduledEvent("evt-r2");
        // A retry whose next attempt time has not arrived yet must not be claimed.
        NotificationEvent notYetDueRetryable = persistFutureRetryScheduledEvent("evt-r3");

        List<NotificationEvent> claimed = claimOutsideOfRepositoryTransaction();

        List<Long> claimedIds = claimed.stream().map(NotificationEvent::getId).toList();
        assertThat(claimedIds).contains(pending.getId(), oldestRetryable.getId(), newestRetryable.getId());
        assertThat(claimedIds).doesNotContain(notYetDueRetryable.getId());
        assertThat(claimedIds.indexOf(oldestRetryable.getId()))
                .isLessThan(claimedIds.indexOf(newestRetryable.getId()));

        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_event WHERE id = ?",
                String.class,
                oldestRetryable.getId());
        // The claim transaction committed without a state change, so the row
        // is still RETRY_SCHEDULED; claiming only locks rows, it does not
        // transition state by itself.
        assertThat(storedStatus).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void shouldClaimStaleDeliveringEventsForRecovery() {
        NotificationEvent stale = notificationEventRepository.save(
                NotificationEvent.newEvent("evt-stale", EventType.CREDIT_CARD_PAYMENT, 1, null, "{}", null));
        stale.markDelivering();
        notificationEventRepository.save(stale);

        // Simulates a crashed delivery: the event has been DELIVERING for
        // longer than the lease allows.
        jdbcTemplate.update(
                "UPDATE notification_event SET updated_at = now() - interval '1 hour' WHERE id = ?",
                stale.getId());

        List<NotificationEvent> claimed = claimOutsideOfRepositoryTransaction();

        assertThat(claimed).extracting(NotificationEvent::getId).contains(stale.getId());
    }

    @Test
    void shouldRespectClaimLimit() throws InterruptedException {
        persistDueRetryScheduledEvent("evt-l1");
        persistDueRetryScheduledEvent("evt-l2");
        persistDueRetryScheduledEvent("evt-l3");

        List<NotificationEvent> claimed = new TransactionTemplate(transactionManager).execute(status ->
                notificationEventRepository.claimDeliverable(2, Instant.now(), staleThreshold()));

        assertThat(claimed).hasSize(2);
    }

    @Test
    void shouldNotClaimEventsLockedByAnotherWorker() throws Exception {
        persistDueRetryScheduledEvent("evt-c1");
        persistDueRetryScheduledEvent("evt-c2");

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstClaimDone = new CountDownLatch(1);
            CountDownLatch releaseFirstClaim = new CountDownLatch(1);

            Future<?> firstWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                List<NotificationEvent> claimed =
                        notificationEventRepository.claimDeliverable(100, Instant.now(), staleThreshold());
                assertThat(claimed).isNotEmpty();
                firstClaimDone.countDown();
                await(releaseFirstClaim);
            }));

            assertThat(firstClaimDone.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> secondWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        // The first worker still holds the row locks, so all
                        // claimable rows are skipped and nothing is claimed.
                        List<NotificationEvent> claimed =
                                notificationEventRepository.claimDeliverable(100, Instant.now(), staleThreshold());
                        assertThat(claimed).isEmpty();
                    }));

            secondWorker.get(10, TimeUnit.SECONDS);
            releaseFirstClaim.countDown();
            firstWorker.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectConcurrentModificationsViaOptimisticLocking() {
        NotificationEvent saved = notificationEventRepository.save(
                NotificationEvent.newEvent("evt-ol", EventType.CASH_WITHDRAWAL, 1, null, "{}", null));

        NotificationEvent firstLoad = notificationEventRepository.findById(saved.getId()).orElseThrow();
        NotificationEvent secondLoad = notificationEventRepository.findById(saved.getId()).orElseThrow();

        firstLoad.markDelivering();
        notificationEventRepository.save(firstLoad);

        secondLoad.markDelivering();
        assertThatThrownBy(() -> notificationEventRepository.save(secondLoad))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    /**
     * The claim operation holds row locks until the surrounding transaction
     * commits, so tests that inspect claimed rows afterwards must run the
     * claim in its own committed transaction.
     */
    private List<NotificationEvent> claimOutsideOfRepositoryTransaction() {
        return new TransactionTemplate(transactionManager).execute(status ->
                notificationEventRepository.claimDeliverable(100, Instant.now(), staleThreshold()));
    }

    private static Instant staleThreshold() {
        return Instant.now().minusSeconds(300);
    }

    private NotificationEvent persistDueRetryScheduledEvent(String eventId) throws InterruptedException {
        NotificationEvent event =
                NotificationEvent.newEvent(eventId, EventType.CREDIT_CARD_PAYMENT, 1, null, "{}", null);
        event.markDelivering();
        event.scheduleRetry(Instant.now().minusSeconds(60));
        NotificationEvent saved = notificationEventRepository.save(event);
        // Guarantees a strictly greater created_at for the next event so that
        // "oldest first" ordering is deterministic.
        Thread.sleep(50);
        return saved;
    }

    private NotificationEvent persistFutureRetryScheduledEvent(String eventId) {
        NotificationEvent event =
                NotificationEvent.newEvent(eventId, EventType.CREDIT_CARD_PAYMENT, 1, null, "{}", null);
        event.markDelivering();
        event.scheduleRetry(Instant.now().plusSeconds(3600));
        return notificationEventRepository.save(event);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for latch", e);
        }
    }
}
