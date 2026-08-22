package com.lokesh_codes.expense_tracker_backend.entity;

/**
 * What an account is allowed to do.
 *
 * <p>Only two levels, deliberately. A third ("auditor", "support") is easy to
 * add and hard to remove once screens branch on it, so it waits until something
 * actually needs it.
 *
 * <p>An {@code ADMIN} manages accounts: who exists, what they may do, and what
 * they have been doing. It grants no sight of anyone's money. Transactions,
 * budgets and categories stay scoped to their owner for every role, which is
 * why {@link com.lokesh_codes.expense_tracker_backend.service.CurrentUserService}
 * is still the only way those services resolve a user.
 */
public enum Role {

    ADMIN,
    MEMBER;

    /** The Spring Security authority, which expects the {@code ROLE_} prefix. */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Reads a stored or submitted value, tolerantly.
     *
     * <p>Rows written before roles were an enum hold {@code "USER"}, and the
     * startup normaliser in
     * {@link com.lokesh_codes.expense_tracker_backend.service.UserAccountBootstrap}
     * rewrites them. Mapping the legacy value here as well means a row that
     * escaped the rewrite reads as a member rather than throwing.
     */
    public static Role parse(String raw) {
        if (raw == null) {
            return MEMBER;
        }
        String normalised = raw.trim().toUpperCase();
        return switch (normalised) {
            case "ADMIN", "ROLE_ADMIN" -> ADMIN;
            case "MEMBER", "ROLE_MEMBER", "USER", "ROLE_USER", "" -> MEMBER;
            default -> throw new IllegalArgumentException("Unknown role: " + raw);
        };
    }
}
