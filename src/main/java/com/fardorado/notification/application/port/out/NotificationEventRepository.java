package com.fardorado.notification.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

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
     * Finds a notification event by its canonical identity, but only if it is
     * owned by the given client. Ownership runs through the matched
     * subscription ({@code notification_event.subscription_id ->
     * subscription.client_id}); an event that matched no subscription has no
     * owner and is therefore never returned.
     *
     * <p>Returning an empty result for both "does not exist" and "belongs to
     * another client" is deliberate: the API must not reveal the existence of
     * another client's resource.</p>
     */
    Optional<NotificationEvent> findByEventIdAndClientId(String eventId, String clientId);

    /**
     * Returns one page of the given client's notification events, newest
     * first, together with each event's attempt count and the HTTP status of
     * its most recent delivery attempt.
     *
     * @param from   inclusive lower bound on creation time, or {@code null}
     * @param to     inclusive upper bound on creation time, or {@code null}
     * @param status status filter, or {@code null} for every status
     */
    PagedResult<NotificationEventSummaryResult> findSummariesByClientId(
            String clientId,
            Instant from,
            Instant to,
            NotificationEventStatus status,
            int page,
            int size);

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
