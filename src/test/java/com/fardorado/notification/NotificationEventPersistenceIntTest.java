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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationEventPersistenceIntTest {

    @Autowired
    private NotificationEventRepository notificationEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSaveAndFindEventWithJsonbPayload() {
        NotificationEvent saved = notificationEventRepository.save(
                NotificationEvent.newEvent(EventType.CREDIT_CARD_PAYMENT, "{\"card_last_four\": \"1234\"}"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0);

        NotificationEvent loaded = notificationEventRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getEventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(loaded.getStatus()).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(loaded.getPayload()).isEqualTo("{\"card_last_four\": \"1234\"}");

        String payloadType = jdbcTemplate.queryForObject(
                "SELECT pg_typeof(payload)::text FROM notification_event WHERE id = ?",
                String.class,
                saved.getId());
        assertThat(payloadType).isEqualTo("jsonb");
    }

    @Test
    void shouldClaimRetryEligibleEventsOldestFirstExcludingOtherStatuses() throws InterruptedException {
        NotificationEvent oldestRetryable = persistRetryScheduledEvent();
        NotificationEvent newestRetryable = persistRetryScheduledEvent();
        NotificationEvent pending =
                notificationEventRepository.save(NotificationEvent.newEvent(EventType.CREDIT_TRANSFER, "{}"));

        List<NotificationEvent> claimed = notificationEventRepository.claimRetryEligible(100);

        List<Long> claimedIds = claimed.stream().map(NotificationEvent::getId).toList();
        assertThat(claimedIds).contains(oldestRetryable.getId(), newestRetryable.getId());
        assertThat(claimedIds).doesNotContain(pending.getId());
        assertThat(claimedIds.indexOf(oldestRetryable.getId()))
                .isLessThan(claimedIds.indexOf(newestRetryable.getId()));

        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_event WHERE id = ?",
                String.class,
                oldestRetryable.getId());
        assertThat(storedStatus).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void shouldRespectClaimLimit() throws InterruptedException {
        persistRetryScheduledEvent();
        persistRetryScheduledEvent();
        persistRetryScheduledEvent();

        List<NotificationEvent> claimed = notificationEventRepository.claimRetryEligible(2);

        assertThat(claimed).hasSize(2);
        assertThat(claimed)
                .allSatisfy(event -> assertThat(event.getStatus())
                        .isEqualTo(NotificationEventStatus.RETRY_SCHEDULED));
    }

    @Test
    void shouldNotClaimEventsLockedByAnotherWorker() throws Exception {
        persistRetryScheduledEvent();
        persistRetryScheduledEvent();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstClaimDone = new CountDownLatch(1);
            CountDownLatch releaseFirstClaim = new CountDownLatch(1);

            Future<?> firstWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                List<NotificationEvent> claimed = notificationEventRepository.claimRetryEligible(100);
                assertThat(claimed).isNotEmpty();
                firstClaimDone.countDown();
                await(releaseFirstClaim);
            }));

            assertThat(firstClaimDone.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> secondWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        // The first worker still holds the row locks, so all
                        // retry-eligible rows are skipped and nothing is claimed.
                        List<NotificationEvent> claimed = notificationEventRepository.claimRetryEligible(100);
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
        NotificationEvent saved =
                notificationEventRepository.save(NotificationEvent.newEvent(EventType.CASH_WITHDRAWAL, "{}"));

        NotificationEvent firstLoad = notificationEventRepository.findById(saved.getId()).orElseThrow();
        NotificationEvent secondLoad = notificationEventRepository.findById(saved.getId()).orElseThrow();

        firstLoad.markDelivering();
        notificationEventRepository.save(firstLoad);

        secondLoad.markDelivering();
        assertThatThrownBy(() -> notificationEventRepository.save(secondLoad))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private NotificationEvent persistRetryScheduledEvent() throws InterruptedException {
        NotificationEvent event = NotificationEvent.newEvent(EventType.CREDIT_CARD_PAYMENT, "{}");
        event.markDelivering();
        event.scheduleRetry();
        NotificationEvent saved = notificationEventRepository.save(event);
        // Guarantees a strictly greater created_at for the next event so that
        // "oldest first" ordering is deterministic.
        Thread.sleep(50);
        return saved;
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
