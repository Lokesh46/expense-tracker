package com.lokesh_codes.expense_tracker_backend.DTO;

import java.time.Instant;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityLog;

/**
 * One audit row, as the client sees it.
 *
 * <p>{@code label} and {@code adverse} are derived server-side so the wording
 * and the "this is a problem" judgement live in one place. A client that keeps
 * its own copy of the enum drifts from it the first time one is added.
 */
public record ActivityLogDTO(
        Long id,
        Instant occurredAt,
        ActivityAction action,
        String label,
        boolean adverse,
        String username,
        String actor,
        String detail,
        String ipAddress,
        String userAgent) {

    public static ActivityLogDTO from(ActivityLog log) {
        return new ActivityLogDTO(
                log.getId(),
                log.getOccurredAt(),
                log.getAction(),
                log.getAction().label(),
                log.getAction().isAdverse(),
                log.getUsername(),
                log.getActor(),
                log.getDetail(),
                log.getIpAddress(),
                log.getUserAgent());
    }
}
