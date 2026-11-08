package com.hospitalqueue.common.dto;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paged lists. Serializable because it is stored in the Redis cache.
 *
 * @param content       the items of this page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total number of items across all pages
 * @param totalPages    total number of pages
 * @param <T>           item type
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)
        implements Serializable {

    /** Converts a Spring Data page into a PageResponse, mapping each entity to its DTO. */
    public static <S, T> PageResponse<T> of(Page<S> source, Function<S, T> mapper) {
        List<T> mapped = source.getContent().stream().map(mapper).toList();
        return new PageResponse<>(mapped, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
}
