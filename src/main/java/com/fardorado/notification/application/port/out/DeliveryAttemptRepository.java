package com.fardorado.notification.application.port.out;

import java.util.List;

import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;

/**
 * Output port for persisting and reading delivery attempts.
 */
public interface DeliveryAttemptRepository {

    DeliveryAttempt save(DeliveryAttempt deliveryAttempt);

    List<DeliveryAttempt> findByNotificationEventIdOrderByAttemptNumber(Long notificationEventId);
}
