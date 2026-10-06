package com.fardorado.notification.adapter.in.web.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * A notification event with its full delivery-attempt history.
 */
@Schema(description = "Notification event details and attempt history")
public record NotificationEventDetailResponseDto(

        @Schema(description = "Identifier of the notification event", example = "EVT001")
        String id,

        @Schema(description = "Business event type", example = "CREDIT_CARD_PAYMENT")
        EventType eventType,

        @Schema(description = "When the event was first received")
        Instant createdAt,

        @Schema(description = "Current delivery status", example = "FAILED")
        NotificationEventStatus status,

        @Schema(description = "Number of delivery attempts made so far", example = "3")
        int attemptCount,

        @Schema(description = "HTTP status of the most recent attempt", example = "500")
        Integer lastHttpStatus,

        @Schema(description = "Webhook the event is delivered to",
                example = "https://example.com/webhook")
        String webhookUrl,

        @Schema(description = "Append-only attempt history, ordered by attempt number")
        List<DeliveryAttemptResponseDto> attempts) {
}
