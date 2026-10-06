package com.fardorado.notification.application.command;

import com.fardorado.notification.domain.model.notification.EventType;

/**
 * Command to ingest a source event received from the event broker.
 *
 * @param eventId the canonical identity of the source event
 * @param eventType the business event type
 * @param eventVersion the version of the event contract/schema
 * @param correlationId correlation identifier propagated for logging/tracing
 * @param content the event payload content
 * @param clientId the logical identifier of the client the event belongs to
 */
public record ProcessNotificationEventCommand(
        String eventId,
        EventType eventType,
        int eventVersion,
        String correlationId,
        String content,
        String clientId) {
}
