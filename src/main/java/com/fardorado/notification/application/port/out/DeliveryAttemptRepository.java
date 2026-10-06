package com.fardorado.notification.application.port.out;

import java.util.List;
import java.util.Optional;

import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;

/**
 * Output port for persisting and reading delivery attempts.
 *
 * <p>Delivery attempts are append-only: every retry creates an additional
 * record and previous records are never overwritten.</p>
 */
public interface DeliveryAttemptRepository {

    DeliveryAttempt save(DeliveryAttempt deliveryAttempt);

    Optional<DeliveryAttempt> findById(Long id);

    List<DeliveryAttempt> findByNotificationEventIdOrderByAttemptNumber(Long notificationEventId);

    /**
     * Returns the attempt number to use for the next delivery attempt of the
     * given notification event (previous highest attempt number plus one,
     * or {@code 1} if no attempt exists yet).
     */
    int nextAttemptNumber(Long notificationEventId);

    /**
     * Returns the delivery attempts of the given notification event that are
     * currently in the given status. Used, for example, to fail the
     * {@code IN_PROGRESS} attempts of a stale delivery before recovering it.
     */
    List<DeliveryAttempt> findByNotificationEventIdAndStatus(
            Long notificationEventId,
            DeliveryAttemptStatus status);
}
