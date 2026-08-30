package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.lokesh_codes.expense_tracker_backend.DTO.StatementPreviewDTO;

/**
 * Holds the most recent preview so it can be read from a different device.
 *
 * <p>The workflow this exists for: the statement is on a phone, and the person
 * reading the result is at a desk. Without this, the preview is only ever seen
 * by the browser that did the uploading.
 *
 * <p><strong>In memory, and only in memory.</strong> Never the database, never
 * the disk. A statement's text is the most private thing this application
 * handles, and the one place it is guaranteed not to outlive the process is the
 * heap. On a free-tier instance that spins down after a quarter hour idle, that
 * is a short life indeed — which is the point.
 *
 * <p><strong>Only redacted previews are kept.</strong> An unredacted one is
 * returned to the browser that asked for it and then forgotten. Retention is for
 * carrying a layout between devices, and a redacted preview carries the layout
 * perfectly; keeping the real values would be storing somebody's bank statement
 * to save them a copy and paste.
 */
@Component
public class StatementPreviewStore {

    /**
     * Long enough to walk to another device, short enough that a forgotten
     * preview is gone before anyone could go looking for it.
     */
    private static final Duration TTL = Duration.ofMinutes(30);

    /** One per administrator. There is no case for keeping a history. */
    private final Map<String, Entry> latest = new ConcurrentHashMap<>();

    private record Entry(StatementPreviewDTO preview, Instant storedAt) {
    }

    /** Keeps a preview for later collection. Unredacted previews are ignored. */
    public void keep(String username, StatementPreviewDTO preview) {
        if (!preview.redacted()) {
            latest.remove(username);
            return;
        }
        latest.put(username, new Entry(preview, Instant.now()));
    }

    /** The last preview this administrator took, if it has not expired. */
    public Optional<StatementPreviewDTO> lastFor(String username) {
        expireOldEntries();
        return Optional.ofNullable(latest.get(username)).map(Entry::preview);
    }

    /** Forgets it now rather than waiting for the clock. */
    public void discard(String username) {
        latest.remove(username);
    }

    private void expireOldEntries() {
        Instant cutoff = Instant.now().minus(TTL);
        latest.entrySet().removeIf(entry -> entry.getValue().storedAt().isBefore(cutoff));
    }
}
