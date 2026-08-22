package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;

import jakarta.persistence.criteria.Predicate;

/**
 * Builds the where-clause for a transaction search.
 *
 * <p>Filtering used to happen in the browser after downloading every
 * transaction, which stops working once an account has more than a few
 * thousand rows.
 */
final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    static Specification<Transaction> forUser(Integer userId, TransactionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always scoped to the caller. This is the security boundary, not a filter.
            predicates.add(cb.equal(root.get("user").get("id"), userId));

            if (filter != null) {
                if (filter.getCategoryId() != null) {
                    predicates.add(cb.equal(root.get("category").get("id"), filter.getCategoryId()));
                }
                if (filter.getFrom() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.getFrom()));
                }
                if (filter.getTo() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.getTo()));
                }
                if (filter.getMinAmount() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.getMinAmount()));
                }
                if (filter.getMaxAmount() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.getMaxAmount()));
                }
                if (hasText(filter.getPaymentMethod())) {
                    predicates.add(cb.equal(root.get("paymentMethod"), filter.getPaymentMethod()));
                }
                if (hasText(filter.getSearch())) {
                    String pattern = "%" + filter.getSearch().trim().toLowerCase() + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("description")), pattern),
                            cb.like(cb.lower(cb.coalesce(root.get("comments"), "")), pattern)));
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
