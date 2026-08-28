package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.service.crypto.BlindIndex;

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

    static Specification<Transaction> forUser(Integer userId, TransactionFilter filter,
            BlindIndex blindIndex) {
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
                if (filter.getType() != null) {
                    predicates.add(cb.equal(root.get("type"), filter.getType()));
                }
                if (filter.getPossibleDuplicate() != null) {
                    predicates.add(cb.equal(root.get("possibleDuplicate"),
                            filter.getPossibleDuplicate()));
                }
                if (hasText(filter.getSearch())) {
                    addTextSearch(predicates, root, cb, filter.getSearch(), blindIndex);
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Matches free text against the search index rather than the description.
     *
     * <p>The description and comments are encrypted, so there is nothing in those
     * columns a {@code LIKE} could usefully compare against. What is comparable is
     * the keyed digest of each word, which is what {@code search_tokens} holds.
     * The comparison still happens in the database, so paging and sorting are
     * unaffected — which is the whole reason for keeping an index at all rather
     * than decrypting and filtering in Java.
     *
     * <p>Every word in the query must be present. That is stricter than the old
     * behaviour, which matched the phrase as a substring, and it is what a user
     * typing two words almost always means.
     */
    private static void addTextSearch(List<Predicate> predicates,
            jakarta.persistence.criteria.Root<Transaction> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            String search,
            BlindIndex blindIndex) {

        List<String> digests = blindIndex.queryDigests(search);
        if (digests.isEmpty()) {
            return;
        }

        for (String digest : digests) {
            // The stored column is space-delimited and space-padded, so matching
            // " digest " lands on a whole entry and never on part of its neighbour.
            predicates.add(cb.like(root.get("searchTokens"), "% " + digest + " %"));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
