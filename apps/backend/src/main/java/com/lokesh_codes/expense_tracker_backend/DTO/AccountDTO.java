package com.lokesh_codes.expense_tracker_backend.DTO;

import java.time.Instant;

import com.lokesh_codes.expense_tracker_backend.entity.AccountStatus;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;

/**
 * Your own account, as returned by {@code /api/account/me}.
 *
 * <p>The frontend can read its role from the token, but it must not decide
 * anything on that basis alone — a token is a claim, not an authority. This
 * endpoint is the authoritative answer, and it is also how the UI notices that
 * an administrator changed something under it.
 */
public record AccountDTO(
        Integer id,
        String username,
        String email,
        Role role,
        AccountStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        String lastLoginIp,
        long loginCount) {

    public static AccountDTO from(User user) {
        return new AccountDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.status(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getLastLoginIp(),
                user.getLoginCount());
    }
}
