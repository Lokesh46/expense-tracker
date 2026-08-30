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

    /**
     * Stored when a description has no merchant in it — marking the row as
     * examined, so it is not mistaken for one the backfill has yet to reach.
     * Never matches a real digest, which is always base64.
     */
    static final String NO_MERCHANT = " ";

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
        indexMerchant(transaction);
    }

    /**
     * Works out which merchant the row is about, and stores it two ways: the
     * name to show, encrypted, and a digest to group by, which SQL can reach.
     *
     * <p>A description with no merchant in it stores the {@code NO_MERCHANT}
     * sentinel rather than null. Null is what the backfill looks for, so a
     * reference-only row left null would be re-examined on every boot for the
     * rest of its life.
     */
    private void indexMerchant(Transaction transaction) {
        String merchant = MerchantKey.of(transaction.getDescription());

        transaction.setMerchantName(merchant);
        transaction.setMerchantHash(
                merchant == null ? NO_MERCHANT : blindIndex.keyedDigest(merchant));
    }

    /**
     * The digest to look a description's merchant up by, or null when it has no
     * merchant to look up.
     *
     * <p>For an import, which needs every merchant in a file before it starts
     * building rows so that history can be fetched in one query rather than one
     * per row. Null rather than the sentinel: there is nothing to ask about.
     */
    public String merchantHashFor(String description) {
        String merchant = MerchantKey.of(description);
        return merchant == null ? null : blindIndex.keyedDigest(merchant);
    }
}
