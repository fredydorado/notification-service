package com.fardorado.notification.domain.model.delivery;

import java.time.Instant;
import java.util.Objects;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;

/**
 * A single attempt to deliver a notification event.
 *
 * <p>Retries are represented as additional delivery attempts for the same
 * notification event, each with the next {@code attemptNumber}. Previous
 * attempts are never overwritten (append-only history). The only delivery
 * channel is WEBHOOK, so the channel is not modeled explicitly.</p>
 */
public class DeliveryAttempt {

    private final Long id;
    private final Long notificationEventId;
    private final int attemptNumber;
    private String errorMessage;
    private Instant completedAt;
    /**
     * Opaque concurrency token managed by the persistence layer. The domain
     * never modifies it; it is carried so that stale updates can be detected
     * at the persistence boundary.
     */
    private final Long version;
    private DeliveryAttemptStatus status;

    /**
     * Creates a new, not yet persisted delivery attempt. Its initial status is
     * {@link DeliveryAttemptStatus#IN_PROGRESS}.
     */
    public static DeliveryAttempt startAttempt(
            Long notificationEventId,
            int attemptNumber) {
        return new DeliveryAttempt(
                null, notificationEventId, DeliveryAttemptStatus.IN_PROGRESS, attemptNumber, null, null, null);
    }

    /**
     * Reconstitutes a delivery attempt from persisted state.
     */
    public DeliveryAttempt(
            Long id,
            Long notificationEventId,
            DeliveryAttemptStatus status,
            int attemptNumber,
            String errorMessage,
            Instant completedAt,
            Long version) {
        this.id = id;
        this.notificationEventId = Objects.requireNonNull(notificationEventId,
                "notificationEventId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be greater than zero");
        }
        this.attemptNumber = attemptNumber;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
        this.version = version;
    }

    /**
     * Marks the attempt as successfully completed.
     */
    public void markSuccess(Instant completedAt) {
        transitionTo(DeliveryAttemptStatus.SUCCESS, DeliveryAttemptStatus.IN_PROGRESS);
        this.completedAt = completedAt;
    }

    /**
     * Marks the attempt as failed.
     *
     * <p>The error message must not contain credentials, tokens or any other
     * sensitive information.</p>
     */
    public void markFailed(String errorMessage, Instant completedAt) {
        transitionTo(DeliveryAttemptStatus.FAILED, DeliveryAttemptStatus.IN_PROGRESS);
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }

    private void transitionTo(DeliveryAttemptStatus target, DeliveryAttemptStatus... allowedSources) {
        for (DeliveryAttemptStatus source : allowedSources) {
            if (this.status == source) {
                this.status = target;
                return;
            }
        }
        throw new InvalidStatusTransitionException(
                "Cannot transition delivery attempt from %s to %s".formatted(this.status, target));
    }

    public Long getId() {
        return id;
    }

    public Long getNotificationEventId() {
        return notificationEventId;
    }

    public DeliveryAttemptStatus getStatus() {
        return status;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
