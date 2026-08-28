package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.lokesh_codes.expense_tracker_backend.exception.TooManyRequestsException;

/**
 * A per-account allowance for the bulk endpoints.
 *
 * <p>Import and export are the two requests that move a whole ledger at once,
 * which makes them both the cheapest way to scrape an account and the cheapest
 * way to exhaust a small instance. Everything else here is one row at a time and
 * needs no allowance.
 *
 * <p>Held in memory rather than in the database. The service runs as a single
 * instance, so a shared store would buy nothing, and a rate limiter that writes
 * a row per request is its own load problem. The consequence is that the
 * allowance resets on redeploy: acceptable, because the limit exists to bound
 * sustained abuse, not to be an accounting record.
 *
 * <p>A sliding window rather than a fixed one. A fixed hourly window lets a
 * caller spend the whole allowance at 10:59 and the whole of the next at 11:01,
 * which is twice the limit in two minutes -- exactly the burst the limit is
 * meant to prevent.
 */
@Service
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * Idle buckets are swept once the map grows past this. Bounded because the
     * key includes an account id, and an instance that has served many accounts
     * would otherwise hold one entry for each of them forever.
     */
    private static final int SWEEP_THRESHOLD = 5_000;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /**
     * Records one use of {@code key} and fails if it exceeds the allowance.
     *
     * @param message what the caller is told; it should say what to do next,
     *                because a bare "too many requests" reads as a fault
     */
    public void require(String key, int maxPerWindow, String message) {
        if (maxPerWindow <= 0) {
            return; // Disabled by configuration.
        }

        if (hits.size() > SWEEP_THRESHOLD) {
            sweep();
        }

        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        // Locked on the account's own deque, so two accounts never contend.
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= maxPerWindow) {
                throw new TooManyRequestsException(message);
            }
            window.addLast(now);
        }
    }

    /** Drops buckets whose every entry has aged out. */
    private void sweep() {
        Instant cutoff = Instant.now().minus(WINDOW);
        hits.entrySet().removeIf(entry -> {
            Deque<Instant> window = entry.getValue();
            synchronized (window) {
                while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                    window.pollFirst();
                }
                return window.isEmpty();
            }
        });
    }
}
