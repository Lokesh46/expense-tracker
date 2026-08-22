package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.ActivityFilter;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityLog;
import com.lokesh_codes.expense_tracker_backend.repository.ActivityLogRepository;
import com.lokesh_codes.expense_tracker_backend.security.RequestContext;

/**
 * Writes and reads the audit trail.
 *
 * <p>Records are written inside whatever transaction the caller is already in,
 * on purpose. The alternative — a separate transaction per entry, so the log
 * survives a rollback — sounds safer but records things that never happened. A
 * log that says an account was deleted when the delete failed is worse than no
 * log.
 */
@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);

    /** Newest-first, so a truncated export is the useful half rather than the oldest. */
    private static final int MAX_EXPORT_ROWS = 10_000;

    private final ActivityLogRepository repository;
    private final RequestContext requestContext;
    private final int retentionDays;

    public ActivityLogService(ActivityLogRepository repository,
            RequestContext requestContext,
            @Value("${app.activity.retention-days:180}") int retentionDays) {
        this.repository = repository;
        this.requestContext = requestContext;
        this.retentionDays = retentionDays;
    }

    // ------------------------------------------------------------------ write

    /** Something the account did to itself, or that happened to it. */
    public void record(ActivityAction action, String username, String detail) {
        write(action, username, null, detail);
    }

    /** Something an administrator did to somebody else's account. */
    public void recordBy(ActivityAction action, String username, String actor, String detail) {
        write(action, username, actor, detail);
    }

    private void write(ActivityAction action, String username, String actor, String detail) {
        ActivityLog entry = new ActivityLog();
        entry.setOccurredAt(Instant.now());
        entry.setAction(action);
        // Bounded because a failed sign-in records the username as typed, and
        // what gets typed at a login form is not always a username.
        entry.setUsername(truncate(username, 60));
        entry.setActor(truncate(actor, 60));
        entry.setDetail(truncate(detail, 300));
        entry.setIpAddress(requestContext.ipAddress());
        entry.setUserAgent(requestContext.userAgent());
        repository.save(entry);
    }

    // ------------------------------------------------------------------- read

    /**
     * Paged search. {@code restrictToUsername} is the security boundary for a
     * member reading their own history; administrators pass null.
     */
    @Transactional(readOnly = true)
    public Page<ActivityLog> search(ActivityFilter filter, String restrictToUsername, Pageable pageable) {
        return repository.findAll(ActivityLogSpecifications.matching(filter, restrictToUsername), pageable);
    }

    @Transactional(readOnly = true)
    public List<ActivityLog> recentFor(String username, int limit) {
        return repository.findByUsernameOrderByOccurredAtDesc(
                username, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "occurredAt")));
    }

    @Transactional(readOnly = true)
    public long countSince(ActivityAction action, Instant since) {
        return repository.countByActionAndOccurredAtAfter(action, since);
    }

    /**
     * The same rows as {@link #search}, as CSV.
     *
     * <p>Capped rather than streamed. An audit log is the one table with no upper
     * bound on size, and an uncapped export on a free-tier instance with 512 MB is
     * a way to take the service down. The cap is reported in the file itself, so a
     * truncated export cannot be mistaken for a complete one.
     */
    @Transactional(readOnly = true)
    public String exportCsv(ActivityFilter filter, String restrictToUsername) {
        Pageable capped = PageRequest.of(0, MAX_EXPORT_ROWS,
                Sort.by(Sort.Direction.DESC, "occurredAt"));

        Page<ActivityLog> page = search(filter, restrictToUsername, capped);

        StringBuilder csv = new StringBuilder();
        csv.append(CsvSupport.row("When", "Event", "Account", "Performed by", "Detail",
                "IP address", "Client"));

        for (ActivityLog entry : page.getContent()) {
            csv.append(CsvSupport.row(
                    entry.getOccurredAt(),
                    entry.getAction().label(),
                    entry.getUsername(),
                    entry.getActor() == null ? "" : entry.getActor(),
                    entry.getDetail() == null ? "" : entry.getDetail(),
                    entry.getIpAddress() == null ? "" : entry.getIpAddress(),
                    entry.getUserAgent() == null ? "" : entry.getUserAgent()));
        }

        if (page.getTotalElements() > MAX_EXPORT_ROWS) {
            csv.append(CsvSupport.row(
                    "Truncated: showing the most recent " + MAX_EXPORT_ROWS + " of "
                            + page.getTotalElements() + " matching entries. Narrow the date range.",
                    "", "", "", "", "", ""));
        }

        return csv.toString();
    }

    // -------------------------------------------------------------- retention

    /**
     * Trims the log nightly.
     *
     * <p>Runs half an hour after the recurring sweep so two write-heavy jobs are
     * not competing on a free-tier database that is allowed one small compute
     * instance.
     */
    @Scheduled(cron = "${app.activity.purge-cron:0 45 2 * * *}")
    @Transactional
    public void purgeOldEntries() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int removed = repository.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Purged {} activity entries older than {} days", removed, retentionDays);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
