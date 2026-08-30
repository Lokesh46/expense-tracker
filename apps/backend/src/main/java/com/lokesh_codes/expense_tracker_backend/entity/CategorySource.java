package com.lokesh_codes.expense_tracker_backend.entity;

/**
 * How a transaction came to be filed where it is.
 *
 * <p>Kept so the review screen can say why, and so a category that looks wrong
 * can explain itself rather than just being wrong. A guess the user never saw
 * is a different thing from a decision they made, and only one of the two
 * should teach the next import anything.
 */
public enum CategorySource {

    /** A filing rule the user wrote. Their instruction, so never queried again. */
    RULE,

    /** Where this user has filed this merchant before. */
    HISTORY,

    /** A category column in the imported file naming a category that exists. */
    FILE,

    /** Chosen by hand, on the form or in review. */
    MANUAL,

    /** Nothing matched; the row landed in Uncategorised. */
    NONE
}
