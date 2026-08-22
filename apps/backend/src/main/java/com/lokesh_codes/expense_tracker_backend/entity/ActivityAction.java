package com.lokesh_codes.expense_tracker_backend.entity;

/**
 * The things worth writing down.
 *
 * <p>Security-relevant events and account changes only. Ordinary use — recording
 * an expense, opening a page — is not logged: it would bury the events that
 * matter under noise, and it would put a member's spending into a table an
 * administrator can read, which is exactly what this design avoids.
 */
public enum ActivityAction {

    LOGIN_SUCCEEDED("Signed in"),
    LOGIN_FAILED("Failed sign-in"),
    ACCOUNT_LOCKED("Locked after failed attempts"),
    ACCOUNT_UNLOCKED("Unlocked"),

    REGISTERED("Registered"),
    USER_CREATED("Created by an administrator"),
    ACCOUNT_DELETED("Deleted"),

    ROLE_CHANGED("Role changed"),
    ACCOUNT_SUSPENDED("Suspended"),
    ACCOUNT_REINSTATED("Reinstated"),

    EMAIL_CHANGED("Email changed"),
    PASSWORD_CHANGED("Password changed"),
    PASSWORD_RESET("Password reset by an administrator"),
    SESSIONS_REVOKED("Sessions revoked");

    private final String label;

    ActivityAction(String label) {
        this.label = label;
    }

    /** Human wording, so the client does not have to keep its own copy. */
    public String label() {
        return label;
    }

    /** Whether this event describes something going wrong, for highlighting. */
    public boolean isAdverse() {
        return this == LOGIN_FAILED || this == ACCOUNT_LOCKED || this == ACCOUNT_SUSPENDED;
    }
}
