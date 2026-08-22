package com.lokesh_codes.expense_tracker_backend.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A recurring monthly spending cap for one category.
 *
 * <p>The limit is not tied to a specific month: it applies to every month until
 * changed. Spend is computed on read from the transactions in the month being
 * viewed, so historical months stay accurate without storing a row per month.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "budgets", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "category_id" }))
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "monthly_limit", precision = 19, scale = 2, nullable = false)
    private BigDecimal monthlyLimit;
}
