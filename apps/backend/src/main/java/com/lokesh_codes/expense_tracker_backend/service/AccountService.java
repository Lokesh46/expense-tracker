package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.AccountDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ActivityFilter;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityLog;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * What an account can do to itself, whatever its role.
 *
 * <p>Separate from {@link UserAdminService} because the two answer different
 * questions and have different boundaries. Everything here is scoped to the
 * caller by construction — the id never comes from the request — so there is no
 * ownership check to forget.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final CurrentUserService currentUser;
    private final ActivityLogService activity;
    private final PasswordEncoder passwordEncoder;

    public AccountService(UserRepository users,
            CurrentUserService currentUser,
            ActivityLogService activity,
            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.currentUser = currentUser;
        this.activity = activity;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public AccountDTO me() {
        return AccountDTO.from(currentUser.require());
    }

    /**
     * Your own recent activity.
     *
     * <p>Worth having for its own sake: seeing "signed in from an address you do
     * not recognise" is how someone finds out their password has leaked, and it
     * does not need an administrator to notice it for them.
     */
    @Transactional(readOnly = true)
    public Page<ActivityLog> myActivity(ActivityFilter filter, Pageable pageable) {
        // The username is taken from the security context, never the request, so
        // no filter value can widen the result beyond the caller's own rows.
        return activity.search(filter, currentUser.require().getUsername(), pageable);
    }

    @Transactional
    public AccountDTO updateEmail(String rawEmail) {
        User user = currentUser.require();
        String email = (rawEmail == null || rawEmail.isBlank()) ? null : rawEmail.trim();

        if (email != null && !email.equalsIgnoreCase(user.getEmail())
                && users.findByEmail(email).isPresent()) {
            throw new ConflictException("That email address is already registered.");
        }

        user.setEmail(email);
        activity.record(ActivityAction.EMAIL_CHANGED, user.getUsername(),
                email == null ? "Removed" : "Set to " + email);

        return AccountDTO.from(users.save(user));
    }

    /**
     * Changes your own password.
     *
     * <p>Ends every session, including this one. That is the intended behaviour
     * rather than an oversight: someone changing their password usually wants
     * whoever else had it to stop being signed in, and there is no way to end
     * only the other sessions when sessions are stateless tokens.
     */
    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        User user = currentUser.require();

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            // Not a 401: the request is authenticated, it is the supplied password
            // that is wrong. A 401 would make the client discard a valid token.
            throw new ConflictException("That is not your current password.");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ConflictException("The new password must be different from the current one.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setSessionsValidFrom(Instant.now());
        users.save(user);

        activity.record(ActivityAction.PASSWORD_CHANGED, user.getUsername(), null);
    }
}
