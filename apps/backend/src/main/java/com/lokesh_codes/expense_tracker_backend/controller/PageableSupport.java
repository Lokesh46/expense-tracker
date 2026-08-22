package com.lokesh_codes.expense_tracker_backend.controller;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Keeps a client-supplied {@code sort} within what the endpoint means to expose.
 *
 * <p>{@code Pageable} is bound straight from the query string, so {@code ?sort=}
 * names any property the entity happens to have. Left alone that is an ordering
 * bug waiting to happen — {@code ?sort=password} is a valid Criteria sort and
 * orders the list by password hash, which is a small but real oracle — and an
 * unknown name throws a 500 from deep inside Hibernate rather than a 400.
 *
 * <p>So the sort is filtered against a whitelist and falls back to the endpoint's
 * default. Unrecognised names are dropped rather than rejected: a client sorting
 * by something that no longer exists should see a sensible list, not an error.
 */
final class PageableSupport {

    /** Page sizes are capped so one request cannot ask for the whole table. */
    private static final int MAX_SIZE = 200;

    private PageableSupport() {
    }

    static Pageable sanitise(Pageable requested, Set<String> allowed, Sort fallback) {
        List<Sort.Order> kept = requested.getSort().stream()
                .filter(order -> allowed.contains(order.getProperty()))
                .toList();

        Sort sort = kept.isEmpty() ? fallback : Sort.by(kept);
        int size = Math.min(Math.max(requested.getPageSize(), 1), MAX_SIZE);

        return PageRequest.of(requested.getPageNumber(), size, sort);
    }
}
