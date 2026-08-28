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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A template that produces transactions on a schedule — rent, a subscription,
 * a standing order.
 *
 * <p>{@code nextRunDate} is the next date a transaction is still owed for. The
 * generator advances it one period at a time, so a rule that was dormant for
 * months still produces every transaction it missed rather than a single
 * catch-up entry.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "recurring_transactions")
public class RecurringTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Encrypted at rest, like the transactions this rule produces. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    private String description;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    private String currency;

    private String paymentMethod;

    /** Encrypted at rest, like the transactions this rule produces. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096)
    private String comments;

    // Written as varchar rather than left to Hibernate, which would make it
    // an H2 ENUM pinned to today's values -- see SchemaRepair for what that
    // costs when a value is added later.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private Frequency frequency;

    /** The next date this rule still owes a transaction for. */
    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    /** Optional end date; the rule stops producing after this. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean active = true;
}
