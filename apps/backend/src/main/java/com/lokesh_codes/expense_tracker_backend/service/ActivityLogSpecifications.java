package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.lokesh_codes.expense_tracker_backend.DTO.ActivityFilter;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityLog;

import jakarta.persistence.criteria.Predicate;

/** Builds the where-clause for an activity search. */
final class ActivityLogSpecifications {

    private ActivityLogSpecifications() {
    }

    /**
     * When {@code restrictToUsername} is given, the result can only ever contain
     * that account's rows. It is passed separately from the filter so a member
     * reading their own history cannot widen it by sending a username of their
     * choosing — the boundary is not something the request can influence.
     */
    static Specification<ActivityLog> matching(ActivityFilter filter, String restrictToUsername) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (restrictToUsername != null) {
                predicates.add(cb.equal(root.get("username"), restrictToUsername));
            }

            if (filter != null) {
                if (restrictToUsername == null && filter.username() != null && !filter.username().isBlank()) {
                    predicates.add(cb.equal(
                            cb.lower(root.get("username")),
                            filter.username().trim().toLowerCase()));
                }
                if (filter.action() != null) {
                    predicates.add(cb.equal(root.get("action"), filter.action()));
                }
                if (filter.from() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
                }
                if (filter.to() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
                }
                if (Boolean.TRUE.equals(filter.adverseOnly())) {
                    // Derived from the enum rather than listed here, so adding an
                    // adverse action cannot be half-implemented.
                    List<ActivityAction> adverse = java.util.Arrays.stream(ActivityAction.values())
                            .filter(ActivityAction::isAdverse)
                            .toList();
                    predicates.add(root.get("action").in(adverse));
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
