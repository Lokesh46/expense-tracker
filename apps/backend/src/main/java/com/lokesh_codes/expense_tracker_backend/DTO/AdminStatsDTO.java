package com.lokesh_codes.expense_tracker_backend.DTO;

/**
 * The numbers across the top of the admin overview.
 *
 * <p>Counted in the database rather than by loading every user, so the screen
 * costs the same with ten accounts as with ten thousand.
 */
public record AdminStatsDTO(
        long totalUsers,
        long admins,
        long members,
        long active,
        long suspended,
        long locked,
        long joinedLast7Days,
        long joinedLast30Days,
        long signInsLast24Hours,
        long failedSignInsLast24Hours) {
}
