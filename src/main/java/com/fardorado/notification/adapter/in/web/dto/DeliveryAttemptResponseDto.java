package com.fardorado.notification.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;

/**
 * One entry of a notification event's delivery-attempt history.
 */
@Schema(description = "A single delivery attempt")
public record DeliveryAttemptResponseDto(

        @Schema(description = "Attempt number, starting at 1", example = "1")
        int attempt,

        @Schema(description = "Outcome of this attempt", example = "FAILED")
        DeliveryAttemptStatus status,

        @Schema(description = "HTTP status returned by the webhook; null when no response was received",
                example = "500")
        Integer httpStatus) {
}
