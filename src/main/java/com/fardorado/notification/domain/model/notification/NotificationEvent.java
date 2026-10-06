package com.fardorado.notification.domain.model.notification;

import java.time.Instant;
import java.util.Objects;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;

/**
 * A business event that must be processed by the notification service.
 *
 * <p>{@code eventId} is the canonical identity of the source event
 * ({@code event_id == notification_event_id}): repeated broker delivery of the
 * same source event must not create a second notification event.</p>
 *
 * <p>Retries do not create new events: a failed delivery transitions the event
 * to {@link NotificationEventStatus#RETRY_SCHEDULED} with a
 * {@code nextAttemptAt} until it is completed or definitively fails.</p>
 */
public class NotificationEvent {

    private final Long id;
    private final String eventId;
    private final EventType eventType;
    private final int eventVersion;
    private final String correlationId;
    private final String payload;
    private final Long subscriptionId;
    /**
     * When the event was first persisted. Assigned by the persistence
     * layer, so it is {@code null} on a not yet persisted event.
     */
    private final Instant createdAt;
    /**
     * Opaque concurrency token managed by the persistence layer. The domain
     * never modifies it; it is carried so that stale updates can be detected
     * at the persistence boundary.
     */
    private final Long version;
    private NotificationEventStatus status;
    private Instant nextAttemptAt;

    /**
     * Creates a new, not yet persisted notification event. Its initial status
     * is {@link NotificationEventStatus#PENDING}.
     */
    public static NotificationEvent newEvent(
            String eventId,
            EventType eventType,
            int eventVersion,
            String correlationId,
            String payload,
            Long subscriptionId) {
        return new NotificationEvent(
                null,
                eventId,
                eventType,
                eventVersion,
                correlationId,
                NotificationEventStatus.PENDING,
                payload,
                subscriptionId,
                null,
                null,
                null);
    }

    /**
     * Reconstitutes a notification event from persisted state.
     */
    public NotificationEvent(
            Long id,
            String eventId,
            EventType eventType,
            int eventVersion,
            String correlationId,
            NotificationEventStatus status,
            String payload,
            Long subscriptionId,
            Instant nextAttemptAt,
            Instant createdAt,
            Long version) {
        this.id = id;
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be greater than zero");
        }
        this.eventVersion = eventVersion;
        this.correlationId = correlationId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.subscriptionId = subscriptionId;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * Starts (or resumes, after a scheduled retry) the delivery of this event.
     */
    public void markDelivering() {
        transitionTo(
                NotificationEventStatus.DELIVERING,
                NotificationEventStatus.PENDING,
                NotificationEventStatus.RETRY_SCHEDULED);
        this.nextAttemptAt = null;
    }

    /**
     * Schedules a retry after a failed delivery attempt.
     *
     * @param nextAttemptAt earliest time the next delivery attempt may run
     */
    public void scheduleRetry(Instant nextAttemptAt) {
        transitionTo(
                NotificationEventStatus.RETRY_SCHEDULED,
                NotificationEventStatus.DELIVERING);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    }

    /**
     * Marks the event as successfully processed.
     */
    public void markCompleted() {
        transitionTo(NotificationEventStatus.COMPLETED, NotificationEventStatus.DELIVERING);
        this.nextAttemptAt = null;
    }

    /**
     * Marks the event as definitively failed. No further automatic retry is
     * scheduled.
     */
    public void markFailed() {
        transitionTo(
                NotificationEventStatus.FAILED,
                NotificationEventStatus.DELIVERING,
                NotificationEventStatus.RETRY_SCHEDULED);
        this.nextAttemptAt = null;
    }

    /**
     * Marks a freshly ingested event as failed because no active subscription
     * matched it. There is nothing to deliver, so the event is terminally
     * failed rather than left pending forever.
     */
    public void markUnmatched() {
        transitionTo(NotificationEventStatus.FAILED, NotificationEventStatus.PENDING);
    }

    /**
     * Re-queues a definitively failed event for processing, as part of an
     * explicit replay/reprocessing operation.
     */
    public void replay() {
        transitionTo(NotificationEventStatus.PENDING, NotificationEventStatus.FAILED);
        this.nextAttemptAt = null;
    }

    private void transitionTo(NotificationEventStatus target, NotificationEventStatus... allowedSources) {
        for (NotificationEventStatus source : allowedSources) {
            if (this.status == source) {
                this.status = target;
                return;
            }
        }
        throw new InvalidStatusTransitionException(
                "Cannot transition notification event from %s to %s".formatted(this.status, target));
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public NotificationEventStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
