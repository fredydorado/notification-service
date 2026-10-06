package com.fardorado.notification.adapter.in.web.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * A notification event as it appears in a list. The event payload is
 * deliberately not exposed here.
 */
@Schema(description = "Summary of a notification event")
public record NotificationEventSummaryResponseDto(

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

        @Schema(description = "HTTP status of the most recent attempt; null when no response was received",
                example = "500")
        Integer lastHttpStatus) {
}
