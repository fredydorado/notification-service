package com.fardorado.notification.adapter.in.web.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A page of results with stable pagination metadata.
 */
@Schema(description = "A page of results")
public record PagedResponseDto<T>(

        @Schema(description = "The records on this page")
        List<T> items,

        @Schema(description = "Zero-based page number", example = "0")
        int page,

        @Schema(description = "Page size used for this response", example = "20")
        int size,

        @Schema(description = "Total number of matching records", example = "1")
        long totalElements,

        @Schema(description = "Total number of pages available", example = "1")
        int totalPages) {
}
