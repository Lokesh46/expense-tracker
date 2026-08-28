package com.lokesh_codes.expense_tracker_backend.entity;

import jakarta.persistence.Column;
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
 * A standing instruction for filing an imported transaction.
 *
 * <p>Before this, an import trusted whatever text sat in the CSV's category
 * column, and created a category for anything it had not seen. One typo in a
 * bank export became a permanent category, and a statement that does not
 * categorise at all — which is most of them — filed everything under "Other".
 *
 * <p>Rules are owned by one user and never shared. What somebody calls their
 * spending is as personal as the spending.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "category_rules")
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The category a matching transaction is filed under. Always the same user's. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** The text to look for. Compared case-insensitively; see {@link MatchType}. */
    @Column(nullable = false, length = 120)
    private String pattern;

    // Written as varchar rather than left to Hibernate, which would make it
    // an H2 ENUM pinned to today's values -- see SchemaRepair for what that
    // costs when a value is added later.
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, columnDefinition = "varchar(20)")
    private MatchType matchType = MatchType.CONTAINS;

    /**
     * Lowest number wins. Order matters because rules overlap: "amazon" and
     * "amazon prime" both match a Prime charge, and which one is meant is a
     * decision only the user can make.
     */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;
}
