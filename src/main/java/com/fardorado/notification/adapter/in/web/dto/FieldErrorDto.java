package com.fardorado.notification.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One field-level validation failure.
 */
@Schema(description = "A single field-level validation failure")
public record FieldErrorDto(

        @Schema(description = "Name of the rejected parameter or field", example = "size")
        String field,

        @Schema(description = "Why the value was rejected", example = "must be greater than 0")
        String message) {
}
