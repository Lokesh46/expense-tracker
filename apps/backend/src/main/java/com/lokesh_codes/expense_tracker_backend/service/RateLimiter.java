package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p><strong>An allowance is spent on work done, not on requests received.</strong>
 * A hit starts provisional: {@link #settle} confirms it once the work happened,
 * {@link #refund} hands it back when it did not, and a request carrying bytes
 * already charged for is not charged again. All three exist because the first
 * version charged upfront and never gave anything back, so one locked PDF and
 * four password attempts locked an account out for an hour having imported
 * nothing -- and, because only successful imports are written to the activity
 * log, left nothing anywhere to explain why.
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

    /**
     * One charged attempt.
     *
     * <p>The fingerprint is what a repeat request is recognised by, and is
     * cleared once the attempt it paid for has finished. Mutable, but only ever
     * under the lock its deque is held by.
     */
    private static final class Hit {
        private final Instant at;
        private String fingerprint;

        private Hit(Instant at, String fingerprint) {
            this.at = at;
            this.fingerprint = fingerprint;
        }
    }

    private final Map<String, Deque<Hit>> hits = new ConcurrentHashMap<>();

    /** Records one use of {@code key}, where nothing identifies the payload. */
    public void require(String key, int maxPerWindow, String message) {
        require(key, null, maxPerWindow, message);
    }

    /**
     * Records one use of {@code key} and fails if it exceeds the allowance.
     *
     * @param fingerprint identifies the payload, or null when there is nothing
     *                    to identify it by. A fingerprint already charged for
     *                    and not yet settled is the same attempt arriving
     *                    again, and is not charged twice: one upload can take
     *                    several requests -- a locked PDF answered on the
     *                    second try, a click repeated while a cold instance was
     *                    still reading the first -- and that is one import.
     * @param message     what the caller is told; it should say what to do next,
     *                    because a bare "too many requests" reads as a fault
     */
    public void require(String key, String fingerprint, int maxPerWindow, String message) {
        if (maxPerWindow <= 0) {
            return; // Disabled by configuration.
        }

        if (hits.size() > SWEEP_THRESHOLD) {
            sweep();
        }

        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        Deque<Hit> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        // Locked on the account's own deque, so two accounts never contend.
        synchronized (window) {
            prune(window, cutoff);

            if (fingerprint != null && find(window, fingerprint) != null) {
                return;
            }
            if (window.size() >= maxPerWindow) {
                throw new TooManyRequestsException(message);
            }
            window.addLast(new Hit(now, fingerprint));
        }
    }

    /**
     * Gives back the hit charged for {@code fingerprint}.
     *
     * <p>For the attempt that asked for work and got none: a file that was not
     * readable, a PDF that would not open, a request that threw. Charging those
     * is what makes the limit fire for someone who has imported nothing, which
     * from the outside is indistinguishable from the feature being broken.
     *
     * <p>The rule callers follow is the one that can be explained to the person
     * hitting the limit: a request that produced nothing costs nothing. Reading
     * a file that turns out to be unusable is work, and refunding it does leave
     * a way to make an instance read files for free -- but it is a bounded one,
     * behind authentication and the multipart size limit, and the alternative
     * was an account locked out for an hour over a feature it had never once
     * got to work. Sustained request abuse is a different problem and wants a
     * different limit; this one is here to bound how often a whole ledger can
     * be moved.
     */
    public void refund(String key, String fingerprint) {
        Deque<Hit> window = hits.get(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            Hit charged = find(window, fingerprint);
            if (charged != null) {
                window.remove(charged);
            }
        }
    }

    /**
     * Confirms the hit charged for {@code fingerprint} once its work is done.
     *
     * <p>The hit stays and keeps counting; what it loses is the free pass it
     * grants to the same bytes arriving again. Without this, a file that
     * imported successfully could be sent back unlimited times for nothing --
     * a full parse and insert each time, which is the exhaustion the allowance
     * is here to prevent.
     */
    public void settle(String key, String fingerprint) {
        Deque<Hit> window = hits.get(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            Hit charged = find(window, fingerprint);
            if (charged != null) {
                charged.fingerprint = null;
            }
        }
    }

    /** Drops buckets whose every entry has aged out. */
    private void sweep() {
        Instant cutoff = Instant.now().minus(WINDOW);
        hits.entrySet().removeIf(entry -> {
            Deque<Hit> window = entry.getValue();
            synchronized (window) {
                prune(window, cutoff);
                return window.isEmpty();
            }
        });
    }

    private static void prune(Deque<Hit> window, Instant cutoff) {
        while (!window.isEmpty() && window.peekFirst().at.isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    /** The newest hit still carrying {@code fingerprint}, or null. */
    private static Hit find(Deque<Hit> window, String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        // Newest first: the hit a caller means is the one it just charged.
        for (Iterator<Hit> it = window.descendingIterator(); it.hasNext();) {
            Hit hit = it.next();
            if (Objects.equals(hit.fingerprint, fingerprint)) {
                return hit;
            }
        }
        return null;
    }
}
