package com.fardorado.notification.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The standardized error body returned by every failing endpoint.
 *
 * <p>It deliberately carries no stack trace, SQL, schema detail or internal
 * class name. Clients branch on {@code code}, which is stable, rather than on
 * the human-readable {@code message}.</p>
 */
@Schema(description = "Standardized error response")
public record ErrorResponseDto(

        @Schema(description = "Stable, machine-readable error code", example = "RESOURCE_NOT_FOUND")
        String code,

        @Schema(description = "Short human-readable summary", example = "Resource not found")
        String message,

        @Schema(description = "More detail about what went wrong when available")
        String description,

        @Schema(description = "Path of the request that failed", example = "/notification_events/EVT001")
        String path,

        @Schema(description = "When the error was generated")
        OffsetDateTime datetime,

        @Schema(description = "Correlation identifier for troubleshooting across logs")
        String correlationId,

        @Schema(description = "Field-level validation failures, when applicable")
        List<FieldErrorDto> errors) {
}
