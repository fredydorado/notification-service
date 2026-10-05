package com.fardorado.notification.domain.model.notification;

import java.util.Objects;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;

/**
 * A business event that must be processed by the notification service.
 *
 * <p>Retries do not create new events: a failed delivery transitions the event
 * to {@link NotificationEventStatus#RETRY_SCHEDULED} until it is completed or
 * definitively fails.</p>
 */
public class NotificationEvent {

    private final Long id;
    private final EventType eventType;
    private final String payload;
    /**
     * Opaque concurrency token managed by the persistence layer. The domain
     * never modifies it; it is carried so that stale updates can be detected
     * at the persistence boundary.
     */
    private final Long version;
    private NotificationEventStatus status;

    /**
     * Creates a new, not yet persisted notification event. Its initial status
     * is {@link NotificationEventStatus#PENDING}.
     */
    public static NotificationEvent newEvent(EventType eventType, String payload) {
        return new NotificationEvent(null, eventType, NotificationEventStatus.PENDING, payload, null);
    }

    /**
     * Reconstitutes a notification event from persisted state.
     */
    public NotificationEvent(
            Long id,
            EventType eventType,
            NotificationEventStatus status,
            String payload,
            Long version) {
        this.id = id;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
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
    }

    /**
     * Schedules a retry after a failed delivery attempt.
     */
    public void scheduleRetry() {
        transitionTo(
                NotificationEventStatus.RETRY_SCHEDULED,
                NotificationEventStatus.DELIVERING);
    }

    /**
     * Marks the event as successfully processed.
     */
    public void markCompleted() {
        transitionTo(
                NotificationEventStatus.COMPLETED,
                NotificationEventStatus.DELIVERING);
    }

    /**
     * Marks the event as definitively failed.
     */
    public void markFailed() {
        transitionTo(
                NotificationEventStatus.FAILED,
                NotificationEventStatus.DELIVERING,
                NotificationEventStatus.RETRY_SCHEDULED);
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

    public EventType getEventType() {
        return eventType;
    }

    public NotificationEventStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Long getVersion() {
        return version;
    }
}
