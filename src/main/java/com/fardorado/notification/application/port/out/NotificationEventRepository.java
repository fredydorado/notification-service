package com.fardorado.notification.application.port.out;

import java.time.Instant;
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
     * Finds a notification event by the canonical identity of its source
     * event ({@code event_id == notification_event_id}). This is the durable
     * basis for idempotent event ingestion.
     */
    Optional<NotificationEvent> findByEventId(String eventId);

    /**
     * Claims up to {@code limit} events that are ready for delivery work,
     * oldest first. Claimable events are:
     *
     * <ul>
     *   <li>{@code PENDING} events that have not been delivered yet,</li>
     *   <li>{@code RETRY_SCHEDULED} events whose {@code nextAttemptAt} has
     *       arrived (or is unset), and</li>
     *   <li>{@code DELIVERING} events that exceeded the delivery lease
     *       ({@code updatedAt} older than {@code staleThreshold}) and must be
     *       recovered.</li>
     * </ul>
     *
     * <p>Claiming locks the selected rows ({@code FOR UPDATE SKIP LOCKED})
     * so that concurrent workers or service instances never claim the same
     * event. The locks are held until the surrounding transaction commits,
     * so this must be called inside a transaction.</p>
     */
    List<NotificationEvent> claimDeliverable(int limit, Instant now, Instant staleThreshold);
}
