package com.paypilot.commerce.catalog.api.dto;

import java.util.List;

/**
 * Stable pagination envelope. Deliberately not Spring's Page: its JSON shape
 * has churned across versions and leaks sort internals we never asked for.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(items, page, size, totalElements, totalPages,
                page + 1 < totalPages);
    }
}
