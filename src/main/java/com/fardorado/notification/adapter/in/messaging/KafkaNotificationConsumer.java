package com.fardorado.notification.adapter.in.messaging;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.fardorado.notification.application.command.ProcessNotificationEventCommand;
import com.fardorado.notification.application.port.in.ProcessNotificationEventUseCase;
import com.fardorado.notification.domain.model.notification.EventType;

/**
 * The Kafka driving adapter for notification events.
 *
 * <p>Its responsibilities are strictly limited to receiving, deserializing,
 * validating and delegating: subscription matching, delivery and retry
 * handling all live behind the event-processing use case.</p>
 *
 * <p>Reliability rule: the broker message is acknowledged <em>only after</em>
 * the notification event has been durably persisted (the use case runs in a
 * transaction that must commit before the ack). If persistence fails, the
 * exception propagates, the message is not acknowledged and the container's
 * error handler redelivers it.</p>
 *
 * <p>Poison messages (malformed or invalid events) are logged and
 * acknowledged so a single bad event cannot block the partition; this is an
 * explicit, observable decision rather than a silent failure.</p>
 */
@Component
@RequiredArgsConstructor
public class KafkaNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationConsumer.class);

    private final ProcessNotificationEventUseCase processNotificationEventUseCase;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "${notification.kafka.topic:notification-events}")
    public void consume(String message, Acknowledgment acknowledgment) {
        NotificationEventKafkaMessage eventMessage;
        try {
            eventMessage = jsonMapper.readValue(message, NotificationEventKafkaMessage.class);
            validate(eventMessage);
        } catch (JacksonException | IllegalArgumentException e) {
            log.error("event rejected, invalid message, acknowledging poison message", e);
            acknowledgment.acknowledge();
            return;
        }

        try {
            MDC.put("eventId", eventMessage.eventId());
            MDC.put("correlationId", eventMessage.correlationId());
            log.info(
                    "event received: eventId={}, eventType={}, eventVersion={}, clientId={}",
                    eventMessage.eventId(),
                    eventMessage.eventType(),
                    eventMessage.eventVersionOrDefault(),
                    eventMessage.clientId());
            processNotificationEventUseCase.processNotificationEvent(toCommand(eventMessage));
            // Acknowledged only after the use case committed the durable
            // persistence of the event.
            acknowledgment.acknowledge();
        } finally {
            MDC.remove("eventId");
            MDC.remove("correlationId");
        }
    }

    private void validate(NotificationEventKafkaMessage message) {
        requireNonBlank(message.eventId(), "event_id");
        requireNonBlank(message.eventType(), "event_type");
        requireNonBlank(message.content(), "content");
        requireNonBlank(message.clientId(), "client_id");
        try {
            EventType.valueOf(message.eventType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown event_type: %s".formatted(message.eventType()));
        }
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing or blank field: %s".formatted(field));
        }
    }

    private ProcessNotificationEventCommand toCommand(NotificationEventKafkaMessage message) {
        return new ProcessNotificationEventCommand(
                message.eventId(),
                EventType.valueOf(message.eventType().toUpperCase(Locale.ROOT)),
                message.eventVersionOrDefault(),
                message.correlationId(),
                normalizePayload(message.content()),
                message.clientId());
    }

    /**
     * The payload column is JSONB, so the payload must be a valid JSON
     * document: structured JSON content is stored as-is, plain-text content is
     * wrapped as {@code {"content": "..."}}.
     */
    private String normalizePayload(String content) {
        try {
            JsonNode parsed = jsonMapper.readTree(content);
            return parsed.toString();
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode().put("content", content).toString();
        }
    }
}
