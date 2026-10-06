package com.fardorado.notification.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The event envelope consumed from the Kafka topic.
 *
 * <p>Expected field shape (snake_case), matching the source events published
 * by the event broker:</p>
 *
 * <pre>
 * {
 *   "event_id": "EVT001",
 *   "event_type": "credit_card_payment",
 *   "event_version": 1,
 *   "correlation_id": "...",
 *   "content": "Credit card payment received for $150.00",
 *   "client_id": "CLIENT001"
 * }
 * </pre>
 *
 * <p>{@code event_version} and {@code correlation_id} are optional and
 * defaulted/kept {@code null} when absent. Unknown fields (e.g. delivery
 * metadata) are ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationEventKafkaMessage(
        String eventId,
        String eventType,
        Integer eventVersion,
        String correlationId,
        String content,
        String clientId) {

    /**
     * The version of the event contract, defaulting to {@code 1} when the
     * source event does not carry one.
     */
    public int eventVersionOrDefault() {
        return eventVersion == null || eventVersion < 1 ? 1 : eventVersion;
    }
}
