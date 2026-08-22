package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A trimmed page envelope.
 *
 * <p>Spring's {@code Page} serialises a large, unstable structure that changes
 * between versions, so the API exposes only what a client needs.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <S, T> PageResponse<T> from(Page<S> page, java.util.function.Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
