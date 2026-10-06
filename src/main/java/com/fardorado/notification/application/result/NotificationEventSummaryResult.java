package com.fardorado.notification.application.result;

import java.time.Instant;

import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * A notification event as it appears in a list, without the event payload.
 *
 * @param id             the canonical event identity ({@code event_id})
 * @param attemptCount   number of delivery attempts made so far
 * @param lastHttpStatus HTTP status of the most recent attempt, or
 *                       {@code null} when no response was ever received
 */
public record NotificationEventSummaryResult(
        String id,
        EventType eventType,
        Instant createdAt,
        NotificationEventStatus status,
        int attemptCount,
        Integer lastHttpStatus) {
}
