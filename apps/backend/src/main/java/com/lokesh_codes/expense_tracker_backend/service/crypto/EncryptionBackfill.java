package com.lokesh_codes.expense_tracker_backend.service.crypto;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.lokesh_codes.expense_tracker_backend.entity.RecurringTransaction;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.repository.RecurringTransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;
import com.lokesh_codes.expense_tracker_backend.service.TransactionIndexer;

/**
 * Encrypts rows written before encryption existed, and fills in their search
 * index.
 *
 * <p>The schema is updated by Hibernate, which widens the columns but leaves
 * their contents alone. The converter reads a plaintext value happily — that is
 * what keeps the application working the moment it starts — but such a row has
 * no search index, so it cannot be found until it has been rewritten once.
 *
 * <p>Reading and saving is all it takes: the converter encrypts on the way out
 * and the indexer fills the digests, so there is no separate migration to keep
 * correct. Idempotent, because a row that has already been rewritten no longer
 * matches.
 *
 * <p>Off by default. It is a one-time job on an existing database, and leaving it
 * enabled means every boot pages through the whole transactions table to
 * discover there is nothing to do.
 */
@Component
// After SchemaRepair, which clears the way for anything that writes.
@Order(10)
public class EncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfill.class);

    /** Small enough that a free-tier instance is never holding much of the table. */
    private static final int BATCH = 200;

    private final TransactionRepository transactions;
    private final RecurringTransactionRepository recurring;
    private final TransactionIndexer indexer;
    private final boolean enabled;

    public EncryptionBackfill(TransactionRepository transactions,
            RecurringTransactionRepository recurring,
            TransactionIndexer indexer,
            @Value("${app.crypto.backfill-on-start:false}") boolean enabled) {
        this.transactions = transactions;
        this.recurring = recurring;
        this.indexer = indexer;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        log.info("Encryption backfill complete: {} rows rewritten", backfill());
    }

    /**
     * Runs the job and reports how many rows were rewritten.
     *
     * <p>Separate from {@link #run} so it can be invoked directly — by a test, or
     * by hand from a console — without having to enable it at startup.
     */
    public int backfill() {
        // Two passes over the same table, because the two columns arrived with
        // different releases: a row encrypted last time round has a search index
        // already and still has no merchant. Rewriting fills whatever is
        // missing, so the passes overlap harmlessly and the second one only
        // sees what the first did not reach.
        return rewrite(transactions::findBySearchTokensIsNull)
                + rewrite(transactions::findByMerchantHashIsNull)
                + backfillRecurring();
    }

    /**
     * Always page zero, and the query asks only for rows that still need the
     * work. Each pass rewrites what it loads, so those rows drop out of the next
     * query; advancing the page number would step straight over the rows that
     * moved up to take their place.
     *
     * <p>No transaction spanning the whole job. Each batch commits on its own, so
     * a backfill interrupted halfway keeps what it has done rather than starting
     * again, and a large table never becomes one enormous write.
     *
     * <p>This terminates only because {@code index} always leaves a value behind
     * — a row with no merchant in its description stores a sentinel rather than
     * null. A pass whose query still matched the rows it had just written would
     * loop until the instance died.
     */
    private int rewrite(Function<Pageable, List<Transaction>> pending) {
        int total = 0;
        Pageable batch = PageRequest.of(0, BATCH);

        while (true) {
            List<Transaction> rows = pending.apply(batch);
            if (rows.isEmpty()) {
                return total;
            }

            rows.forEach(indexer::index);
            transactions.saveAll(rows);
            total += rows.size();

            if (rows.size() < BATCH) {
                return total;
            }
        }
    }

    private int backfillRecurring() {
        // Recurring rules carry no search index, so there is nothing to test a
        // row against. They are few — one per standing order — so all of them are
        // rewritten, which encrypts any that are still plaintext and leaves the
        // rest byte-for-byte equivalent.
        List<RecurringTransaction> rules = recurring.findAll();
        recurring.saveAll(rules);
        return rules.size();
    }
}
