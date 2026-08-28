package com.lokesh_codes.expense_tracker_backend.service;

import org.springframework.stereotype.Component;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.service.crypto.BlindIndex;

/**
 * Keeps a transaction's derived columns in step with its content.
 *
 * <p>A transaction is built in three places — recorded by hand, imported from a
 * CSV, and generated from a recurring rule — and every one of them has to
 * produce the same derived values, or a row becomes invisible to search, or
 * invisible to duplicate detection, depending only on how it was created. One
 * component called from all three is the difference between that being obvious
 * and it being a bug nobody notices until a user says a transaction they can see
 * is one they cannot find.
 */
@Component
public class TransactionIndexer {

    private final BlindIndex blindIndex;
    private final TransactionFingerprint fingerprint;

    public TransactionIndexer(BlindIndex blindIndex, TransactionFingerprint fingerprint) {
        this.blindIndex = blindIndex;
        this.fingerprint = fingerprint;
    }

    /**
     * Recomputes everything derived from the transaction's own fields.
     *
     * <p>Must run once the user, date, amount and description are all set, and
     * again whenever any of them change — editing a row's amount makes it a
     * different payment as far as duplicate detection is concerned.
     */
    public void index(Transaction transaction) {
        transaction.setSearchTokens(
                blindIndex.tokensFor(transaction.getDescription(), transaction.getComments()));
        transaction.setFingerprint(fingerprint.of(transaction));
    }
}
