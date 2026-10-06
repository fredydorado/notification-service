package com.fardorado.notification.application.command;

import com.fardorado.notification.domain.model.notification.EventType;

/**
 * Command to execute the delivery attempt of one notification event.
 *
 * <p>Carries everything the delivery needs so the external webhook call can
 * run outside any database transaction.</p>
 *
 * @param notificationEventId the persisted notification event to deliver
 * @param deliveryAttemptId the delivery attempt created for this delivery
 * @param webhookUrl the webhook URL to POST the notification to
 * @param eventId the canonical identity of the source event
 * @param eventType the business event type
 * @param eventVersion the version of the event contract/schema
 * @param correlationId correlation identifier propagated for logging/tracing
 * @param payload the notification payload to deliver
 * @param attemptNumber the sequential number of this delivery attempt
 */
public record ProcessDeliveryCommand(
        Long notificationEventId,
        Long deliveryAttemptId,
        String webhookUrl,
        String eventId,
        EventType eventType,
        int eventVersion,
        String correlationId,
        String payload,
        int attemptNumber) {
}
