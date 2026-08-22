package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.lokesh_codes.expense_tracker_backend.DTO.UserFilter;
import com.lokesh_codes.expense_tracker_backend.entity.User;

import jakarta.persistence.criteria.Predicate;

/**
 * Builds the where-clause for the user list.
 *
 * <p>{@link com.lokesh_codes.expense_tracker_backend.entity.AccountStatus} is
 * derived rather than stored, so filtering on it has to be expressed against the
 * columns it is derived from. Doing that here keeps the derivation in one place:
 * the definition in {@code User.status()} and the predicate below must agree, and
 * they are tested together.
 */
final class UserSpecifications {

    private UserSpecifications() {
    }

    static Specification<User> matching(UserFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter != null) {
                if (filter.role() != null) {
                    predicates.add(cb.equal(root.get("role"), filter.role()));
                }

                if (filter.status() != null) {
                    Instant now = Instant.now();
                    var lockedUntil = root.<Instant>get("lockedUntil");
                    Predicate unlocked = cb.or(
                            cb.isNull(lockedUntil),
                            cb.lessThanOrEqualTo(lockedUntil, now));
                    Predicate locked = cb.and(
                            cb.isNotNull(lockedUntil),
                            cb.greaterThan(lockedUntil, now));

                    predicates.add(switch (filter.status()) {
                        // Suspension outranks a lock, matching User.status().
                        case SUSPENDED -> cb.isFalse(root.get("active"));
                        case LOCKED -> cb.and(cb.isTrue(root.get("active")), locked);
                        case ACTIVE -> cb.and(cb.isTrue(root.get("active")), unlocked);
                    });
                }

                if (filter.search() != null && !filter.search().isBlank()) {
                    String pattern = "%" + filter.search().trim().toLowerCase() + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("username")), pattern),
                            // Coalesced because email is optional, and LIKE against
                            // NULL is neither true nor false — the row would vanish.
                            cb.like(cb.lower(cb.coalesce(root.get("email"), "")), pattern)));
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
