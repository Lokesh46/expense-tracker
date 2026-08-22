package com.lokesh_codes.expense_tracker_backend.DTO;

import java.time.Instant;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;

/** How the activity log is narrowed. Every field is optional. */
public record ActivityFilter(
        String username,
        ActivityAction action,
        Instant from,
        Instant to,
        Boolean adverseOnly) {
}
