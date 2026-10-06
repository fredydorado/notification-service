package com.fardorado.notification.application.port.out;

import com.fardorado.notification.domain.model.notification.EventType;

/**
 * Command describing one external webhook notification delivery.
 *
 * @param webhookUrl the webhook URL to POST the notification to
 * @param eventId the canonical identity of the source event
 * @param eventType the business event type
 * @param eventVersion the version of the event contract/schema
 * @param correlationId correlation identifier propagated for logging/tracing
 * @param content the notification payload content
 */
public record WebhookDeliveryCommand(
        String webhookUrl,
        String eventId,
        EventType eventType,
        int eventVersion,
        String correlationId,
        String content) {
}
