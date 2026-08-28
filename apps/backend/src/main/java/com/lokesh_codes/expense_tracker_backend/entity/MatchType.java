package com.lokesh_codes.expense_tracker_backend.entity;

import java.util.Locale;

/**
 * How a category rule compares its pattern against a transaction description.
 *
 * <p>There is deliberately no regular-expression option. A pattern supplied by a
 * user and evaluated on the server against every row of an imported file is a
 * catastrophic-backtracking denial of service waiting to be written by accident,
 * and no amount of validation reliably tells a safe expression from an unsafe
 * one. These three cover what a rule is actually for — recognising a merchant —
 * and each is linear in the length of the input.
 */
public enum MatchType {

    CONTAINS("Contains") {
        @Override
        public boolean matches(String description, String pattern) {
            return normalise(description).contains(normalise(pattern));
        }
    },

    STARTS_WITH("Starts with") {
        @Override
        public boolean matches(String description, String pattern) {
            return normalise(description).startsWith(normalise(pattern));
        }
    },

    EQUALS("Is exactly") {
        @Override
        public boolean matches(String description, String pattern) {
            return normalise(description).equals(normalise(pattern));
        }
    };

    private final String label;

    MatchType(String label) {
        this.label = label;
    }

    /** Human wording, so the client does not have to keep its own copy. */
    public String label() {
        return label;
    }

    public abstract boolean matches(String description, String pattern);

    /**
     * Comparison ignores case and surrounding space. A bank that writes
     * {@code "TESCO STORES"} this month and {@code "Tesco Stores"} the next has
     * not changed which rule should apply.
     */
    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
