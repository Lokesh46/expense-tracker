package com.lokesh_codes.expense_tracker_backend.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.lokesh_codes.expense_tracker_backend.entity.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Query parameters for searching transactions. Every field is optional; a null
 * field contributes no restriction.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionFilter {

    private Integer categoryId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String paymentMethod;

    /** Free text matched against description and comments, case-insensitively. */
    private String search;

    /**
     * Restricts to rows an import flagged as looking like duplicates, so they
     * can be reviewed together. Null means no restriction, which is not the same
     * as false.
     */
    private Boolean possibleDuplicate;

    /** Restricts to expenses or to income. Null means both. */
    private TransactionType type;
}
