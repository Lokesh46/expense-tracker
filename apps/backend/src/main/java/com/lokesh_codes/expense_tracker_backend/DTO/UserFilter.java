package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.AccountStatus;
import com.lokesh_codes.expense_tracker_backend.entity.Role;

/**
 * How the user list is narrowed. Every field is optional; all of them are
 * applied in the database rather than in memory.
 */
public record UserFilter(
        String search,
        Role role,
        AccountStatus status) {
}
