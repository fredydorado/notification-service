package com.fardorado.notification.domain.model.notification;

/**
 * The processing lifecycle of a notification event.
 */
public enum NotificationEventStatus {
    PENDING,
    DELIVERING,
    RETRY_SCHEDULED,
    COMPLETED,
    FAILED
}
