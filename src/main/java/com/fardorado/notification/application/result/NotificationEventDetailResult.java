package com.fardorado.notification.application.result;

import java.time.Instant;
import java.util.List;

import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * A notification event with its delivery-attempt history.
 *
 * @param id             the canonical event identity ({@code event_id})
 * @param webhookUrl     delivery target taken from the matched subscription,
 *                       or {@code null} when no subscription matched
 * @param attempts       append-only attempt history, ordered by attempt number
 */
public record NotificationEventDetailResult(
        String id,
        EventType eventType,
        Instant createdAt,
        NotificationEventStatus status,
        int attemptCount,
        Integer lastHttpStatus,
        String webhookUrl,
        List<DeliveryAttemptResult> attempts) {
}
