package com.lokesh_codes.expense_tracker_backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One thing that happened to one account.
 *
 * <p>There is deliberately no foreign key to {@code users}. An audit trail whose
 * rows vanish with the account they describe cannot answer the question it
 * exists for — "what happened to the account that is no longer here?" — so the
 * username is stored as text and the record outlives the user. It also lets a
 * failed sign-in against a username that never existed be recorded at all,
 * which is the shape a credential-stuffing attempt takes.
 *
 * <p>The trade-off is that a deleted username, if later re-registered, shares a
 * history with its predecessor. Ids are not reused for anything that matters
 * here, and for an audit log "everything that ever happened under this name" is
 * arguably the more honest answer than silently splitting it.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "activity_log", indexes = {
        // The two ways this table is ever read: newest-first overall, and
        // newest-first for one account. Without them the admin screens
        // table-scan a log that only ever grows.
        @Index(name = "idx_activity_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_activity_username", columnList = "username, occurred_at")
})
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Named to avoid {@code AT}, which SQL reserves for {@code AT TIME ZONE}. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityAction action;

    /**
     * The account the event concerns. For a failed sign-in this is the username
     * as typed, which may not exist.
     */
    @Column(nullable = false, length = 60)
    private String username;

    /**
     * The administrator responsible, when someone acted on another account.
     * Null when the account acted on itself, or when the scheduler did.
     */
    @Column(length = 60)
    private String actor;

    /** Short human detail, e.g. {@code "MEMBER to ADMIN"}. Never a secret. */
    @Column(length = 300)
    private String detail;

    /** Wide enough for IPv6, and for the odd proxy that sends a chain. */
    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;
}
