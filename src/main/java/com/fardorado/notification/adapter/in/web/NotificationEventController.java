package com.fardorado.notification.adapter.in.web;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;

import com.fardorado.notification.adapter.in.web.dto.ErrorResponseDto;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventDetailResponseDto;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventSummaryResponseDto;
import com.fardorado.notification.adapter.in.web.dto.PagedResponseDto;
import com.fardorado.notification.adapter.in.web.dto.ReplayNotificationEventResponseDto;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * The notification-events REST contract. This interface owns the complete
 * OpenAPI documentation; the implementation carries none.
 *
 * <p>Every operation is scoped to the calling client, identified by the
 * {@code X-Client-Id} header. A client identifier is never accepted as a
 * query parameter, and an event belonging to another client is reported as
 * {@code 404} so that its existence is not revealed.</p>
 */
@Tag(name = "Notification Events",
        description = "Inspect and replay the notification events of the calling client")
public interface NotificationEventController {

    @Operation(
            summary = "List notification events",
            description = """
                    Returns a page of the calling client's notification events, newest first,
                    optionally filtered by creation date range and delivery status.
                    The event payload is not included.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of notification events"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing client identification",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    ResponseEntity<PagedResponseDto<NotificationEventSummaryResponseDto>> listNotificationEvents(

            @Parameter(description = "Identifier of the calling client", required = true,
                    example = "CLIENT001")
            @NotBlank String clientId,

            @Parameter(description = "Inclusive start of the creation date/time range",
                    example = "2026-10-01T00:00:00Z")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Inclusive end of the creation date/time range",
                    example = "2026-10-05T23:59:59Z")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,

            @Parameter(description = "Filter by delivery status", example = "FAILED")
            NotificationEventStatus deliveryStatus,

            @Parameter(description = "Zero-based page number", example = "0")
            @PositiveOrZero int page,

            @Parameter(description = "Records per page; must not exceed the configured maximum",
                    example = "20")
            @Positive int size);

    @Operation(
            summary = "Get notification event details",
            description = """
                    Returns one notification event belonging to the calling client, together with
                    its append-only delivery-attempt history.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The notification event"),
            @ApiResponse(responseCode = "400", description = "Invalid identifier",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing client identification",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such notification event for the calling client",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    ResponseEntity<NotificationEventDetailResponseDto> getNotificationEvent(

            @Parameter(description = "Identifier of the calling client", required = true,
                    example = "CLIENT001")
            @NotBlank String clientId,

            @Parameter(description = "Identifier of the notification event", required = true,
                    example = "EVT001")
            @NotBlank String notificationEventId);

    @Operation(
            summary = "Replay a failed notification event",
            description = """
                    Requests reprocessing of a definitively failed notification event. The event is
                    transitioned from FAILED back to PENDING and picked up by the existing delivery
                    infrastructure; the call returns immediately and never waits for webhook
                    delivery. Only FAILED events are replayable, and concurrent replay requests for
                    the same event never cause duplicate processing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Replay accepted for async processing"),
            @ApiResponse(responseCode = "400", description = "Invalid identifier",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing client identification",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such notification event for the calling client",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409",
                    description = "The event is not in a replayable state",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    ResponseEntity<ReplayNotificationEventResponseDto> replayNotificationEvent(

            @Parameter(description = "Identifier of the calling client", required = true,
                    example = "CLIENT001")
            @NotBlank String clientId,

            @Parameter(description = "Identifier of the notification event to replay", required = true,
                    example = "EVT001")
            @NotBlank String notificationEventId);
}
