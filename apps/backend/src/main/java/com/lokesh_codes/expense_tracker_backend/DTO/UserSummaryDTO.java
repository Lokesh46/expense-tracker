package com.lokesh_codes.expense_tracker_backend.DTO;

import java.time.Instant;

import com.lokesh_codes.expense_tracker_backend.entity.AccountStatus;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;

/**
 * A row in the user list.
 *
 * <p>Notably absent: anything about money. An administrator manages accounts,
 * not ledgers, so no endpoint in this feature can reach another user's
 * transactions, and this DTO has nowhere to put them even by accident.
 */
public record UserSummaryDTO(
        Integer id,
        String username,
        String email,
        Role role,
        AccountStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        long loginCount,
        Instant lockedUntil) {

    public static UserSummaryDTO from(User user) {
        return new UserSummaryDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.status(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getLoginCount(),
                // Only meaningful while it is in the future; a stale value would
                // read as "locked" on a screen long after the lock expired.
                user.isLocked() ? user.getLockedUntil() : null);
    }
}
