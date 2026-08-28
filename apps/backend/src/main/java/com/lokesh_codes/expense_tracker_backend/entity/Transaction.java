package com.lokesh_codes.expense_tracker_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lokesh_codes.expense_tracker_backend.service.crypto.EncryptedStringConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transactions_user_date", columnList = "user_id, date"),
        // An import looks up every row it is about to add against this pair, so
        // without the index a duplicate check is a table scan per file.
        @Index(name = "idx_transactions_user_fingerprint", columnList = "user_id, fingerprint")
})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", referencedColumnName = "id", nullable = false)
    private Category category;

    /**
     * Encrypted at rest. A merchant name is the most identifying thing in the
     * row — it says where somebody was and what they were doing — so it does not
     * sit in the database in the clear.
     *
     * <p>The column is far wider than the 200 characters a description is allowed
     * to be: ciphertext is base64 of the text plus an IV and an authentication
     * tag, which is roughly twice the size of the longest input.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    private String description;

    /**
     * Money is stored as an exact decimal. It was previously a double, which
     * cannot represent most decimal fractions and drifts once totals are summed.
     *
     * <p>Deliberately not encrypted. Budgets and the dashboard sum this column in
     * the database; encrypting it would move every total into Java over a full
     * result set, which is the thing this codebase has already had to undo once.
     */
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    private String currency;

    /**
     * Whether this is money out or money in. Defaults at the database level so
     * that rows written before the column existed read as expenses, which is
     * what they were.
     *
     * <p>Every total that means "spending" has to filter on this. See
     * {@code TransactionRepository.sumForCategoryBetween}, which is where a
     * budget gets its figure.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10,
            columnDefinition = "varchar(10) default 'EXPENSE'")
    private TransactionType type = TransactionType.EXPENSE;

    /** A calendar date; an expense happens on a day, not at an instant. */
    private LocalDate date;

    private String paymentMethod;

    /** Encrypted at rest for the same reason as the description. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096)
    private String comments;

    /**
     * Keyed digests of the words in the description and comments, so those
     * encrypted fields remain searchable in SQL.
     *
     * <p>Never returned to a client and never shown; it exists only to be matched
     * against. See {@code BlindIndex} for what it can and cannot find.
     */
    @Column(name = "search_tokens", length = 2048)
    private String searchTokens;

    /**
     * A keyed digest of the fields that identify one real payment, used to spot
     * a statement imported twice. See {@code TransactionFingerprint}.
     */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /**
     * Set when an import found a row already carrying this fingerprint.
     *
     * <p>Flagged, not withheld. A row marked this way is a real transaction: it
     * counts toward budgets and dashboard totals like any other, and stays that
     * way until the owner either deletes it or says it is genuine. Two identical
     * coffees on one day is a thing that happens, and no rule can tell that apart
     * from a file imported twice — only the person who spent the money can.
     */
    @Column(name = "possible_duplicate", nullable = false, columnDefinition = "boolean default false")
    private boolean possibleDuplicate = false;
}
