package com.lokesh_codes.expense_tracker_backend.service;

import java.util.Locale;
import java.util.Map;

import com.lokesh_codes.expense_tracker_backend.entity.TransactionType;

/**
 * Reads the many words banks use for "money out" and "money in".
 *
 * <p>Separate from the importer because two callers need it for different
 * reasons: the row parser, to read a direction column, and {@code CsvColumns},
 * to decide whether a column called "Type" holds a direction at all or a payment
 * method. Both have to agree on what counts, or a column claimed as a direction
 * fails on every row.
 *
 * <p>Only unambiguous words are listed. "Payment" and "Purchase" look like they
 * mean money going out, and both were here until a Chase statement showed what
 * that costs: its Type column holds "Sale" and "Payment", so the column was
 * claimed as a direction, a payroll deposit of three thousand dollars was filed
 * as an expense, and the row that said "Sale" was rejected outright. A word that
 * appears in payment-method columns is not a direction, however much it sounds
 * like one.
 */
final class TransactionDirection {

    private static final Map<String, TransactionType> WORDS = Map.ofEntries(
            Map.entry("expense", TransactionType.EXPENSE),
            Map.entry("debit", TransactionType.EXPENSE),
            Map.entry("dr", TransactionType.EXPENSE),
            Map.entry("out", TransactionType.EXPENSE),
            Map.entry("withdrawal", TransactionType.EXPENSE),
            Map.entry("income", TransactionType.INCOME),
            Map.entry("credit", TransactionType.INCOME),
            Map.entry("cr", TransactionType.INCOME),
            Map.entry("in", TransactionType.INCOME),
            Map.entry("deposit", TransactionType.INCOME),
            Map.entry("refund", TransactionType.INCOME));

    private TransactionDirection() {
    }

    /** Whether the value is a word this understands. Used to identify a column. */
    static boolean recognises(String value) {
        return value != null && WORDS.containsKey(normalise(value));
    }

    /**
     * Reads a direction, or throws so the row is reported rather than guessed at.
     */
    static TransactionType parse(String raw) {
        TransactionType type = WORDS.get(normalise(raw));
        if (type == null) {
            throw new IllegalArgumentException(
                    "\"" + raw + "\" is not a transaction type; use Expense or Income");
        }
        return type;
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim();
    }
}
