package com.lokesh_codes.expense_tracker_backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnDefault;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * Unique at the database level, not only in the registration check. Two
     * simultaneous registrations of the same name both pass an application-level
     * "is it taken?" test; only a constraint stops them.
     */
    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(length = 120)
    private String email;

    /** A BCrypt hash. Excluded from toString so it cannot reach a log. */
    @ToString.Exclude
    @Column(nullable = false)
    private String password;

    // Written as varchar rather than left to Hibernate, which would make it
    // an H2 ENUM pinned to today's values -- see SchemaRepair for what that
    // costs when a value is added later.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private Role role;

    /** False when an administrator has switched the account off. */
    private boolean active;

    // ------------------------------------------------------- activity tracking

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_login_ip", length = 60)
    private String lastLoginIp;

    /**
     * Lifetime successful sign-ins. Cheap to keep, and the first thing asked.
     *
     * <p>The column default is not decoration. These columns are NOT NULL and were
     * added to a table that already had rows, and PostgreSQL refuses to add a
     * NOT NULL column to a populated table unless it is told what the existing
     * rows should hold.
     */
    @ColumnDefault("0")
    @Column(name = "login_count", nullable = false)
    private long loginCount;

    @ColumnDefault("0")
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    /** Set once the configured attempt limit is reached; expires on its own. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Tokens issued before this instant are refused.
     *
     * <p>Without it, suspending an account or demoting an administrator would
     * take effect only when their current token expired — up to ninety minutes
     * of continued access after being switched off, which makes the whole
     * management screen advisory rather than real. See
     * {@link com.lokesh_codes.expense_tracker_backend.security.AccountStateFilter}.
     */
    @Column(name = "sessions_valid_from")
    private Instant sessionsValidFrom;

    // ------------------------------------------------------------- derived

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    /**
     * One value instead of three booleans. Suspension outranks a lock: an
     * account an administrator switched off should not read as "try again in ten
     * minutes".
     */
    public AccountStatus status() {
        if (!active) {
            return AccountStatus.SUSPENDED;
        }
        return isLocked() ? AccountStatus.LOCKED : AccountStatus.ACTIVE;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
