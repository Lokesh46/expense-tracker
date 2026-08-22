package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

/**
 * One account in full, for the detail screen.
 *
 * <p>The counts are counts. They say how much the account holds without saying
 * what it holds, which is enough for an administrator to see that an account is
 * in use and not enough to see anybody's spending.
 */
public record UserDetailDTO(
        UserSummaryDTO account,
        int failedLoginAttempts,
        String lastLoginIp,
        long transactionCount,
        long categoryCount,
        long budgetCount,
        long recurringCount,
        List<ActivityLogDTO> recentActivity) {
}
