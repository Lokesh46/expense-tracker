package com.lokesh_codes.expense_tracker_backend.entity;

/**
 * The account's current standing, as one value rather than three booleans.
 *
 * <p>"Can this person sign in?" has more than one answer and the answers are not
 * interchangeable: an administrator turned the account off, or the account
 * turned itself off by failing too many passwords. They need different wording
 * in the UI and different remedies, so they are different states.
 */
public enum AccountStatus {

    /** Signs in normally. */
    ACTIVE,

    /** Switched off by an administrator. Only an administrator can reverse it. */
    SUSPENDED,

    /** Too many failed passwords. Clears itself when the lock expires. */
    LOCKED
}
