package com.lokesh_codes.expense_tracker_backend.entity;

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
 * A spending category, owned by exactly one user.
 *
 * <p>Categories used to be global: every account saw the same list and could
 * rename or delete entries belonging to everyone else. Ownership makes each
 * user's list private, and the unique constraint stops the same name being
 * added twice for one user while leaving other users unaffected.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "name" }))
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    private String name;

    /** Hex colour used by the dashboard so a category keeps its colour when renamed. */
    private String color;

    public Category(User user, String name, String color) {
        this.user = user;
        this.name = name;
        this.color = color;
    }
}
