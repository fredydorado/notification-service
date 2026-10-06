package com.fardorado.notification.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fardorado.notification.application.command.ProcessNotificationEventCommand;
import com.fardorado.notification.application.port.in.ProcessNotificationEventUseCase;
import com.fardorado.notification.domain.model.notification.EventType;

class KafkaNotificationConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ProcessNotificationEventCommand> receivedCommands = new ArrayList<>();
    private final AtomicInteger acknowledgments = new AtomicInteger();

    private final ProcessNotificationEventUseCase useCase = receivedCommands::add;

    private final Acknowledgment acknowledgment = new Acknowledgment() {
        @Override
        public void acknowledge() {
            acknowledgments.incrementAndGet();
        }
    };

    private final KafkaNotificationConsumer consumer = new KafkaNotificationConsumer(useCase, objectMapper);

    @Test
    void shouldDelegateValidEventAndAcknowledge() {
        consumer.consume(
                """
                {
                  "event_id": "EVT001",
                  "event_type": "credit_card_payment",
                  "content": "Credit card payment received for $150.00",
                  "delivery_date": "2024-03-15T09:30:22Z",
                  "delivery_status": "completed",
                  "client_id": "CLIENT001"
                }
                """,
                acknowledgment);

        assertThat(receivedCommands).hasSize(1);
        ProcessNotificationEventCommand command = receivedCommands.getFirst();
        assertThat(command.eventId()).isEqualTo("EVT001");
        assertThat(command.eventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(command.eventVersion()).isEqualTo(1);
        assertThat(command.correlationId()).isNull();
        assertThat(command.content()).isEqualTo("{\"content\":\"Credit card payment received for $150.00\"}");
        assertThat(command.clientId()).isEqualTo("CLIENT001");
        assertThat(acknowledgments.get()).isEqualTo(1);
    }

    @Test
    void shouldPreserveEventVersionAndCorrelationId() {
        consumer.consume(
                """
                {
                  "event_id": "EVT002",
                  "event_type": "credit_transfer",
                  "event_version": 3,
                  "correlation_id": "corr-42",
                  "content": "{\\\"amount\\\": 150}",
                  "client_id": "CLIENT002"
                }
                """,
                acknowledgment);

        assertThat(receivedCommands).hasSize(1);
        assertThat(receivedCommands.getFirst().eventVersion()).isEqualTo(3);
        assertThat(receivedCommands.getFirst().correlationId()).isEqualTo("corr-42");
        // Structured JSON content is stored verbatim.
        assertThat(receivedCommands.getFirst().content()).isEqualTo("{\"amount\":150}");
    }

    @Test
    void shouldAcknowledgePoisonMessageWithoutProcessing() {
        consumer.consume("{ not valid json", acknowledgment);
        consumer.consume(
                """
                {
                  "event_id": "EVT003",
                  "event_type": "unknown_event_type",
                  "content": "irrelevant",
                  "client_id": "CLIENT001"
                }
                """,
                acknowledgment);
        consumer.consume(
                """
                {
                  "event_type": "credit_card_payment",
                  "content": "missing event_id",
                  "client_id": "CLIENT001"
                }
                """,
                acknowledgment);

        assertThat(receivedCommands).isEmpty();
        assertThat(acknowledgments.get()).isEqualTo(3);
    }
}
