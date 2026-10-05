package com.fardorado.notification.application.port.out;

import java.util.List;
import java.util.Optional;

import com.fardorado.notification.domain.model.notification.NotificationEvent;

/**
 * Output port for persisting and reading notification events.
 */
public interface NotificationEventRepository {

    NotificationEvent save(NotificationEvent notificationEvent);

    Optional<NotificationEvent> findById(Long id);

    /**
     * Claims up to {@code limit} events that are eligible for a retry, oldest
     * first. Claiming locks the selected rows so that concurrent workers do
     * not process the same event.
     */
    List<NotificationEvent> claimRetryEligible(int limit);
}
