package com.lokesh_codes.expense_tracker_backend.entity;

/**
 * Which way the money went.
 *
 * <p>The amount is always stored as a positive number; this says how to read it.
 * Before this existed, import took the absolute value of whatever the file said,
 * so a refund, a salary credit and a debit for the same figure were
 * indistinguishable once stored — and a month with a large refund in it reported
 * spending that never happened.
 *
 * <p>Everything written before this defaults to {@code EXPENSE}, which is what
 * those rows were always assumed to be.
 */
public enum TransactionType {

    EXPENSE("Expense"),
    INCOME("Income");

    private final String label;

    TransactionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
