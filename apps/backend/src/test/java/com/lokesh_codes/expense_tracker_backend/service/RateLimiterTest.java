package com.lokesh_codes.expense_tracker_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lokesh_codes.expense_tracker_backend.exception.TooManyRequestsException;

/**
 * What an allowance is spent on.
 *
 * <p>The counting itself was never in doubt — the first version of this class
 * refused the sixth request in an hour exactly as intended. What was wrong was
 * everything it counted: a hit was taken before the request was known to be
 * doing any work, and nothing ever gave one back. So five files that failed to
 * import cost the same as five that worked, and the account was locked out of a
 * feature it had never once used successfully.
 */
class RateLimiterTest {

    private static final String KEY = "import:1";
    private static final String MESSAGE = "Try again shortly.";

    private final RateLimiter limiter = new RateLimiter();

    private void require(String fingerprint) {
        limiter.require(KEY, fingerprint, 2, MESSAGE);
    }

    @Test
    @DisplayName("a refunded attempt costs nothing")
    void refundReturnsTheAllowance() {
        // Three failures where the allowance is two. Under the old behaviour
        // the account is locked out here having imported nothing at all.
        for (int attempt = 1; attempt <= 3; attempt++) {
            String fingerprint = "failed-" + attempt;
            require(fingerprint);
            limiter.refund(KEY, fingerprint);
        }

        assertThatCode(() -> require("good"))
                .as("the allowance after three attempts that did no work")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a settled attempt keeps counting")
    void settledAttemptsStillCount() {
        require("first");
        limiter.settle(KEY, "first");
        require("second");
        limiter.settle(KEY, "second");

        assertThatThrownBy(() -> require("third"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    @DisplayName("the same upload arriving twice is charged once")
    void repeatOfAnUnsettledAttemptIsFree() {
        // The request that has not finished yet. A second one carrying the same
        // bytes is the same upload -- a click repeated while a cold instance was
        // still reading the first, or a tab retrying -- not a second import.
        require("in-flight");
        require("in-flight");
        require("in-flight");

        assertThatCode(() -> require("a different file"))
                .as("one slot should remain: three requests, one upload")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the same upload after the first one finished is charged again")
    void repeatOfASettledAttemptIsCharged() {
        // Settling is what closes the hole the free pass would otherwise open.
        // A file that imported can be sent again -- that is a real second
        // import, a full parse and insert, and it is paid for.
        require("done");
        limiter.settle(KEY, "done");
        require("done");
        limiter.settle(KEY, "done");

        assertThatThrownBy(() -> require("done"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("one account's allowance is its own")
    void allowancesDoNotCross() {
        require("a");
        require("b");

        assertThatCode(() -> limiter.require("import:2", "a", 2, MESSAGE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refunding something never charged does nothing")
    void refundOfAnUnknownKeyIsHarmless() {
        assertThatCode(() -> limiter.refund("import:never-used", "nothing"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.settle("import:never-used", "nothing"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("concurrent requests cannot spend more than the allowance")
    void concurrentRequestsRespectTheLimit() throws Exception {
        // Every hit is written inside the map's remapping function for its key,
        // so the check and the write cannot be split by another thread. Before
        // that, the bucket was fetched from the map and locked separately, and
        // anything happening in the gap -- a sweep, in particular -- could drop
        // a hit that had been counted.
        int threads = 32;
        int allowance = 5;
        var limiter = new RateLimiter();
        var start = new CountDownLatch(1);
        var granted = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);

        try {
            var done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final String fingerprint = "file-" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                        limiter.require(KEY, fingerprint, allowance, MESSAGE);
                        granted.incrementAndGet();
                    } catch (TooManyRequestsException expected) {
                        // One of the ones over the line.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get())
                .as("%d threads against an allowance of %d", threads, allowance)
                .isEqualTo(allowance);
    }

    @Test
    @DisplayName("concurrent refunds give back exactly what they took")
    void concurrentRefundsRestoreTheAllowance() throws Exception {
        int threads = 32;
        var limiter = new RateLimiter();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                final String fingerprint = "failed-" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                        limiter.require(KEY, fingerprint, 4, MESSAGE);
                        limiter.refund(KEY, fingerprint);
                    } catch (TooManyRequestsException expected) {
                        // Refused while others were mid-flight; nothing to undo.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Every attempt was handed back, so the whole allowance is there again.
        for (int attempt = 1; attempt <= 4; attempt++) {
            final String fingerprint = "after-" + attempt;
            assertThatCode(() -> limiter.require(KEY, fingerprint, 4, MESSAGE))
                    .as("attempt %d of 4 after everything was refunded", attempt)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("an allowance of zero is off, not a wall")
    void zeroDisablesTheLimit() {
        for (int attempt = 1; attempt <= 50; attempt++) {
            limiter.require(KEY, "anything", 0, MESSAGE);
        }
    }
}
