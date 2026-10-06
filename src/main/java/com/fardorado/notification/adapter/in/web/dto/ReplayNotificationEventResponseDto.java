package com.fardorado.notification.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Acknowledgement that a replay request has been accepted for asynchronous
 * processing.
 */
@Schema(description = "Replay acknowledgement")
public record ReplayNotificationEventResponseDto(

        @Schema(description = "Identifier of the notification event being replayed", example = "EVT001")
        String notificationEventId,

        @Schema(description = "Status of the event immediately after the replay transition",
                example = "PENDING")
        NotificationEventStatus status) {
}
