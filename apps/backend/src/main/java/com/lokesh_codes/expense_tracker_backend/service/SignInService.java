package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;
import com.lokesh_codes.expense_tracker_backend.security.RequestContext;

/**
 * Everything that happens around a sign-in attempt other than checking the
 * password, which Spring Security does.
 *
 * <p>Kept out of the controller because both outcomes write to the database and
 * both must commit: a failed attempt that is not counted is a lockout that never
 * happens.
 */
@Service
public class SignInService {

    private final UserRepository users;
    private final ActivityLogService activity;
    private final RequestContext requestContext;
    private final int maxFailedAttempts;
    private final int lockoutMinutes;

    public SignInService(UserRepository users,
            ActivityLogService activity,
            RequestContext requestContext,
            @Value("${app.security.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${app.security.lockout-minutes:15}") int lockoutMinutes) {
        this.users = users;
        this.activity = activity;
        this.requestContext = requestContext;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    /**
     * Records a successful sign-in.
     *
     * <p>Also clears the failure counter. Without that, five wrong passwords
     * spread over a year would eventually lock an account that has been signing
     * in successfully throughout.
     */
    @Transactional
    public void recordSuccess(String username) {
        users.findByUsername(username).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            user.setLastLoginIp(requestContext.ipAddress());
            user.setLoginCount(user.getLoginCount() + 1);
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            users.save(user);
        });

        activity.record(ActivityAction.LOGIN_SUCCEEDED, username, null);
    }

    /**
     * Records a failed sign-in and locks the account if it has now failed too
     * many times in a row.
     *
     * <p>An unknown username is logged too, with no counter to increment. Those
     * rows are the ones that show a password-guessing run for what it is.
     *
     * @return how long the account is locked for, or null if it is not
     */
    @Transactional
    public Duration recordFailure(String username) {
        var found = users.findByUsername(username);

        if (found.isEmpty()) {
            activity.record(ActivityAction.LOGIN_FAILED, username, "No such account");
            return null;
        }

        User user = found.get();
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        Duration lockedFor = null;
        if (attempts >= maxFailedAttempts && lockoutMinutes > 0) {
            Instant until = Instant.now().plus(lockoutMinutes, ChronoUnit.MINUTES);
            user.setLockedUntil(until);
            lockedFor = Duration.ofMinutes(lockoutMinutes);
        }

        users.save(user);

        activity.record(ActivityAction.LOGIN_FAILED, username,
                "Attempt %d of %d".formatted(attempts, maxFailedAttempts));

        if (lockedFor != null) {
            activity.record(ActivityAction.ACCOUNT_LOCKED, username,
                    "Locked for %d minutes".formatted(lockoutMinutes));
        }

        return lockedFor;
    }

    /**
     * How much longer the account is locked, for the message shown to the person
     * trying to sign in.
     *
     * <p>Telling them confirms the account exists. That is a real disclosure, and
     * it is accepted here because registration already discloses the same thing
     * — it refuses a taken username by saying so — and because "wrong password"
     * five times running, when the password is right, is the kind of thing that
     * makes people think the application is broken.
     */
    @Transactional(readOnly = true)
    public Duration remainingLockout(String username) {
        return users.findByUsername(username)
                .filter(User::isLocked)
                .map(user -> Duration.between(Instant.now(), user.getLockedUntil()))
                .orElse(null);
    }

    /**
     * Wording for a lockout message: "15 minutes", "1 minute", "under a minute".
     *
     * <p>Rounded up. {@code Duration.toMinutes()} truncates, so a fresh
     * fifteen-minute lock has 14 minutes 59 seconds left and would be announced as
     * "14 minutes" — sending someone back a minute early to be refused again.
     * Overstating by under a minute is the harmless direction.
     */
    public static String describe(Duration duration) {
        if (duration == null) {
            return "a few minutes";
        }
        if (duration.isZero() || duration.isNegative()) {
            return "under a minute";
        }
        long minutes = (duration.toSeconds() + 59) / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
