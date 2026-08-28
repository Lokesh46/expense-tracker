package com.lokesh_codes.expense_tracker_backend.DTO;

/**
 * Which of the day and the month comes first in a slash-separated date.
 *
 * <p>{@code 03/04/2026} is the 3rd of April in most of the world and the 4th of
 * March in the United States, and nothing in the file says which. Guessing is
 * not an option worth taking: the wrong guess is not an error the user can see,
 * it is a transaction quietly filed in the wrong month, and it happens to every
 * ambiguous date in the file at once.
 *
 * <p>So the importer asks. The default matches the rest of the application's
 * date handling, which was already day-first.
 */
public enum DateOrder {

    /** 14/08/2026 is the 14th of August. The default, and most of the world. */
    DAY_FIRST("day first (14/08/2026)"),

    /** 08/14/2026 is the 14th of August. United States exports. */
    MONTH_FIRST("month first (08/14/2026)");

    private final String label;

    DateOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
