package com.fardorado.notification.application.result;

import java.util.List;

/**
 * A page of results, expressed without any persistence-framework type so that
 * pagination never leaks Spring Data across an application port boundary.
 *
 * @param items         the records on this page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total number of matching records
 * @param totalPages    total number of pages for that size
 */
public record PagedResult<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PagedResult<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PagedResult<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
