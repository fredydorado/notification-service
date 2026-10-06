package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "notification.processing.dispatch-enabled=false")
class DeliveryAttemptPersistenceIntTest {

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private NotificationEventRepository notificationEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAttemptAndFindHistoryOrderedByAttemptNumber() {
        Long notificationEventId = persistNotificationEvent("evt-a1");

        DeliveryAttempt first =
                deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));
        DeliveryAttempt second =
                deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 2));

        assertThat(first.getId()).isNotNull();
        assertThat(first.getVersion()).isEqualTo(0);

        List<DeliveryAttempt> history =
                deliveryAttemptRepository.findByNotificationEventIdOrderByAttemptNumber(notificationEventId);

        assertThat(history)
                .extracting(DeliveryAttempt::getAttemptNumber)
                .containsExactly(1, 2);
        assertThat(history)
                .extracting(DeliveryAttempt::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void shouldCompleteAttemptLifecycle() {
        Long notificationEventId = persistNotificationEvent("evt-a2");

        DeliveryAttempt saved =
                deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));

        DeliveryAttempt loaded = deliveryAttemptRepository
                .findByNotificationEventIdOrderByAttemptNumber(saved.getNotificationEventId())
                .getFirst();

        Instant completedAt = Instant.now();
        loaded.markSuccess(completedAt);
        DeliveryAttempt completed = deliveryAttemptRepository.save(loaded);

        assertThat(completed.getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
        assertThat(completed.getErrorMessage()).isNull();
        assertThat(completed.getVersion()).isEqualTo(1);
    }

    @Test
    void shouldProvideNextAttemptNumber() {
        Long notificationEventId = persistNotificationEvent("evt-a3");

        assertThat(deliveryAttemptRepository.nextAttemptNumber(notificationEventId)).isEqualTo(1);

        deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));
        deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 2));

        assertThat(deliveryAttemptRepository.nextAttemptNumber(notificationEventId)).isEqualTo(3);
    }

    @Test
    void shouldFindAttemptsByStatus() {
        Long notificationEventId = persistNotificationEvent("evt-a4");

        DeliveryAttempt inProgress =
                deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));

        List<DeliveryAttempt> found = deliveryAttemptRepository
                .findByNotificationEventIdAndStatus(notificationEventId, DeliveryAttemptStatus.IN_PROGRESS);

        assertThat(found).extracting(DeliveryAttempt::getId).containsExactly(inProgress.getId());
    }

    @Test
    void shouldRejectDuplicateAttemptNumberPerNotificationEvent() {
        Long notificationEventId = persistNotificationEvent("evt-a5");

        deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));

        assertThatThrownBy(() -> deliveryAttemptRepository.save(
                        DeliveryAttempt.startAttempt(notificationEventId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnAttemptNumber() {
        Long notificationEventId = persistNotificationEvent("evt-a6");

        // Bypasses the domain guard to verify the database constraint itself.
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO delivery_attempt
                            (notification_event_id, status, attempt_number, created_at, version)
                        VALUES
                            (?, 'IN_PROGRESS', 0, now(), 0)
                        """,
                        notificationEventId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldEnforceDomainGuardOnAttemptNumber() {
        Long notificationEventId = persistNotificationEvent("evt-a7");

        assertThatThrownBy(() -> DeliveryAttempt.startAttempt(notificationEventId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber");
    }

    @Test
    void shouldNotDeleteNotificationEventWithAttempts() {
        Long notificationEventId = persistNotificationEvent("evt-a8");

        deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM notification_event WHERE id = ?", notificationEventId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldRejectConcurrentModificationsViaOptimisticLocking() {
        Long notificationEventId = persistNotificationEvent("evt-a9");

        DeliveryAttempt saved =
                deliveryAttemptRepository.save(DeliveryAttempt.startAttempt(notificationEventId, 1));

        DeliveryAttempt firstLoad = deliveryAttemptRepository
                .findByNotificationEventIdOrderByAttemptNumber(saved.getNotificationEventId())
                .getFirst();
        DeliveryAttempt secondLoad = deliveryAttemptRepository
                .findByNotificationEventIdOrderByAttemptNumber(saved.getNotificationEventId())
                .getFirst();

        firstLoad.markFailed("webhook endpoint returned HTTP 500", Instant.now());
        deliveryAttemptRepository.save(firstLoad);

        secondLoad.markFailed("webhook endpoint returned HTTP 500", Instant.now());
        assertThatThrownBy(() -> deliveryAttemptRepository.save(secondLoad))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private Long persistNotificationEvent(String eventId) {
        NotificationEvent saved = notificationEventRepository.save(
                NotificationEvent.newEvent(eventId, EventType.CREDIT_CARD_PAYMENT, 1, null,
                        "{\"card_last_four\": \"1234\"}", null));
        return saved.getId();
    }
}
