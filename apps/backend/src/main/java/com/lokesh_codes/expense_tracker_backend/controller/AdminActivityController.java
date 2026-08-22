package com.lokesh_codes.expense_tracker_backend.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.ActivityFilter;
import com.lokesh_codes.expense_tracker_backend.DTO.ActivityLogDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.PageResponse;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.service.ActivityLogService;

/**
 * The audit trail, across every account.
 *
 * <p>Sign-ins, failures, lockouts and account changes. Not what anybody spent —
 * ordinary use is not logged, so this table cannot become a back door into a
 * member's ledger.
 */
@RestController
@RequestMapping("/api/admin/activity")
@PreAuthorize("hasRole('ADMIN')")
public class AdminActivityController {

    private static final Set<String> SORTABLE = Set.of("occurredAt", "action", "username", "id");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "occurredAt");

    private final ActivityLogService activityLog;

    public AdminActivityController(ActivityLogService activityLog) {
        this.activityLog = activityLog;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ActivityLogDTO>> search(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) ActivityAction action,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Boolean adverseOnly,
            @PageableDefault(size = 25) Pageable pageable) {

        var page = activityLog.search(
                filter(username, action, from, to, adverseOnly),
                null,
                PageableSupport.sanitise(pageable, SORTABLE, DEFAULT_SORT));

        return ResponseEntity.ok(PageResponse.from(page, ActivityLogDTO::from));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) ActivityAction action,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Boolean adverseOnly) {

        String csv = activityLog.exportCsv(filter(username, action, from, to, adverseOnly), null);
        String filename = "activity-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * Dates in, instants out.
     *
     * <p>The client sends calendar dates because that is what a date picker
     * produces, and the column is a timestamp. The window is inclusive at both
     * ends — {@code to=2026-08-22} includes everything that happened on the 22nd —
     * because a range that silently excludes its final day is the kind of thing
     * nobody notices until an event is missing from a search.
     *
     * <p>Boundaries are taken in UTC, matching the stored instants. On a
     * single-region deployment the alternative (the server's zone) differs only in
     * how surprising it is when the region changes.
     */
    private ActivityFilter filter(String username, ActivityAction action,
            LocalDate from, LocalDate to, Boolean adverseOnly) {

        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // End-of-day rather than midnight-next-day: the predicate is <=, so the
        // latter would also match an event at exactly 00:00 on the following day.
        Instant toInstant = to == null ? null : to.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);

        return new ActivityFilter(username, action, fromInstant, toInstant, adverseOnly);
    }
}
