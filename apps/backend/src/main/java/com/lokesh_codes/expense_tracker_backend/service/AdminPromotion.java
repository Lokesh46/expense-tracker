package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * Appoints the first administrator from configuration.
 *
 * <p>A fresh deployment has no administrator and no way to appoint one, because
 * only an administrator may promote anybody. {@code ADMIN_USERNAME} is the way in.
 *
 * <p>It is applied at two moments, and the second is the one that matters in
 * practice:
 *
 * <ul>
 *   <li><b>At startup</b>, for an account that already exists — the case where
 *       somebody registered before the variable was set.
 *   <li><b>At registration</b>, the moment the named account is created. Without
 *       this the sequence is register, then set the variable, then restart, then
 *       sign in again — three steps and a redeploy to get the role you were always
 *       going to have.
 * </ul>
 *
 * <p>The exposure is the same either way and worth stating plainly: whoever holds
 * that username gets the role. If a stranger registers it before you do, the
 * promotion lands on them. That is why {@code ADMIN_USERNAME} is declared
 * {@code sync: false} in {@code render.yaml} rather than written there in the
 * open, and why it is worth setting shortly before registering rather than
 * leaving a guessable name configured on an empty database indefinitely.
 */
@Service
public class AdminPromotion {

    private static final Logger log = LoggerFactory.getLogger(AdminPromotion.class);

    /** Written by configuration rather than by a person, and named so in the log. */
    static final String SYSTEM_ACTOR = "system";

    private final UserRepository users;
    private final ActivityLogService activity;
    private final String configuredUsername;

    public AdminPromotion(UserRepository users,
            ActivityLogService activity,
            @Value("${app.admin.username:}") String configuredUsername) {
        this.users = users;
        this.activity = activity;
        this.configuredUsername = configuredUsername == null ? "" : configuredUsername.trim();
    }

    public boolean isConfigured() {
        return !configuredUsername.isEmpty();
    }

    public String configuredUsername() {
        return configuredUsername;
    }

    /**
     * Whether this username is the configured administrator.
     *
     * <p>Compared without case, because a username typed into a registration form
     * is not reliably the same case as one typed into a hosting dashboard, and
     * "the promotion silently did not happen" is a miserable thing to debug.
     */
    public boolean matches(String username) {
        return isConfigured() && configuredUsername.equalsIgnoreCase(username == null ? "" : username.trim());
    }

    /**
     * Promotes the account if configuration names it.
     *
     * <p>Idempotent: an account that is already an administrator is left alone and
     * nothing is logged, so {@code ADMIN_USERNAME} can stay set across restarts
     * without filling the audit trail with repeats.
     *
     * @return true if this call changed the role
     */
    public boolean apply(User user, String reason) {
        if (!matches(user.getUsername()) || user.isAdmin()) {
            return false;
        }

        user.setRole(Role.ADMIN);
        // The account may be mid-session holding a member's token. Revoking makes
        // the new authority apply at its next request rather than at expiry. On a
        // just-registered account there is no session yet and this is a no-op.
        user.setSessionsValidFrom(Instant.now());
        users.save(user);

        activity.recordBy(ActivityAction.ROLE_CHANGED, user.getUsername(), SYSTEM_ACTOR,
                "MEMBER to ADMIN via ADMIN_USERNAME");
        log.info("Promoted '{}' to ADMIN from ADMIN_USERNAME ({})", user.getUsername(), reason);

        return true;
    }
}
