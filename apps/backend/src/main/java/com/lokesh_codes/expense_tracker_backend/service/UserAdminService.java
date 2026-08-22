package com.lokesh_codes.expense_tracker_backend.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.ActivityLogDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.AdminStatsDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.CreateUserRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.UpdateUserRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.UserDetailDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.UserFilter;
import com.lokesh_codes.expense_tracker_backend.DTO.UserSummaryDTO;
import com.lokesh_codes.expense_tracker_backend.entity.AccountStatus;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.BudgetRepository;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.RecurringTransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * Account administration.
 *
 * <p>Every method is guarded here as well as by the URL rule in
 * {@link com.lokesh_codes.expense_tracker_backend.security.JwtSecurityConfig}.
 * The duplication is deliberate: a future controller that forgets the path
 * prefix, or a call from somewhere unexpected, still cannot get through.
 * Path-based rules are easy to get subtly wrong and produce no error when they
 * are.
 *
 * <p>What this class cannot do is read anybody's money. It counts rows and never
 * loads them.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminService {

    private static final int RECENT_ACTIVITY_ROWS = 25;

    private final UserRepository users;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final BudgetRepository budgets;
    private final RecurringTransactionRepository recurring;
    private final ActivityLogService activity;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserRepository users,
            TransactionRepository transactions,
            CategoryRepository categories,
            BudgetRepository budgets,
            RecurringTransactionRepository recurring,
            ActivityLogService activity,
            CategoryService categoryService,
            CurrentUserService currentUser,
            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.transactions = transactions;
        this.categories = categories;
        this.budgets = budgets;
        this.recurring = recurring;
        this.activity = activity;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public Page<User> list(UserFilter filter, Pageable pageable) {
        return users.findAll(UserSpecifications.matching(filter), pageable);
    }

    @Transactional(readOnly = true)
    public UserDetailDTO detail(Integer id) {
        User user = require(id);

        List<ActivityLogDTO> recent = activity.recentFor(user.getUsername(), RECENT_ACTIVITY_ROWS)
                .stream()
                .map(ActivityLogDTO::from)
                .toList();

        return new UserDetailDTO(
                UserSummaryDTO.from(user),
                user.getFailedLoginAttempts(),
                user.getLastLoginIp(),
                transactions.countByUser_Id(id),
                categories.countByUser_Id(id),
                budgets.countByUser_Id(id),
                recurring.countByUser_Id(id),
                recent);
    }

    @Transactional(readOnly = true)
    public AdminStatsDTO stats() {
        Instant now = Instant.now();
        long total = users.count();
        long admins = users.countByRole(Role.ADMIN);
        long suspended = users.countByActive(false);

        // Locked is a live condition rather than a column, so it is counted
        // through the same specification the list filter uses.
        long locked = users.count(
                UserSpecifications.matching(new UserFilter(null, null, AccountStatus.LOCKED)));

        return new AdminStatsDTO(
                total,
                admins,
                total - admins,
                total - suspended - locked,
                suspended,
                locked,
                users.countByCreatedAtAfter(now.minus(7, ChronoUnit.DAYS)),
                users.countByCreatedAtAfter(now.minus(30, ChronoUnit.DAYS)),
                activity.countSince(ActivityAction.LOGIN_SUCCEEDED, now.minus(24, ChronoUnit.HOURS)),
                activity.countSince(ActivityAction.LOGIN_FAILED, now.minus(24, ChronoUnit.HOURS)));
    }

    // ------------------------------------------------------------------ write

    /**
     * Creates an account on someone's behalf, seeded like a self-registration so
     * they do not sign in to empty screens.
     */
    @Transactional
    public UserSummaryDTO create(CreateUserRequest request) {
        String username = request.username().trim();
        if (users.findByUsername(username).isPresent()) {
            throw new ConflictException("That username is already taken.");
        }

        String email = normaliseEmail(request.email());
        if (email != null && users.findByEmail(email).isPresent()) {
            throw new ConflictException("That email address is already registered.");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setActive(request.active() == null || request.active());

        User saved = users.save(user);
        categoryService.seedDefaultsFor(saved);

        activity.recordBy(ActivityAction.USER_CREATED, saved.getUsername(), actor(),
                "Created as " + saved.getRole());

        return UserSummaryDTO.from(saved);
    }

    /**
     * Applies whichever fields were sent. Each change is logged separately, so
     * the trail says what changed rather than that something did.
     */
    @Transactional
    public UserSummaryDTO update(Integer id, UpdateUserRequest request) {
        User user = require(id);
        boolean revokeSessions = false;

        if (request.email() != null) {
            String email = normaliseEmail(request.email());
            boolean takenByAnother = email != null
                    && !email.equalsIgnoreCase(user.getEmail())
                    && users.findByEmail(email).isPresent();
            if (takenByAnother) {
                throw new ConflictException("That email address is already registered.");
            }
            if (!Objects.equals(email, user.getEmail())) {
                activity.recordBy(ActivityAction.EMAIL_CHANGED, user.getUsername(), actor(),
                        email == null ? "Removed" : "Set to " + email);
                user.setEmail(email);
            }
        }

        if (request.role() != null && request.role() != user.getRole()) {
            guardRoleChange(user, request.role());
            activity.recordBy(ActivityAction.ROLE_CHANGED, user.getUsername(), actor(),
                    user.getRole() + " to " + request.role());
            user.setRole(request.role());
            // Authorities are carried in the token. Without revoking, a demoted
            // administrator keeps administrative access until it expires.
            revokeSessions = true;
        }

        if (request.active() != null && request.active() != user.isActive()) {
            guardDeactivation(user, request.active());
            user.setActive(request.active());
            if (request.active()) {
                // Reinstating clears the failure counter, otherwise the account is
                // one wrong password away from locking again.
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                activity.recordBy(ActivityAction.ACCOUNT_REINSTATED, user.getUsername(), actor(), null);
            } else {
                activity.recordBy(ActivityAction.ACCOUNT_SUSPENDED, user.getUsername(), actor(), null);
                revokeSessions = true;
            }
        }

        if (revokeSessions) {
            user.setSessionsValidFrom(Instant.now());
        }

        return UserSummaryDTO.from(users.save(user));
    }

    /**
     * Sets a password directly. There is no mail delivery in this application, so
     * a reset is an administrator handing over a password out of band rather than
     * a link.
     */
    @Transactional
    public void setPassword(Integer id, String newPassword) {
        User user = require(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        // A password change that leaves old tokens working is not a password
        // change; the point of one is usually to end somebody's access.
        user.setSessionsValidFrom(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        users.save(user);

        activity.recordBy(ActivityAction.PASSWORD_RESET, user.getUsername(), actor(), null);
    }

    @Transactional
    public UserSummaryDTO unlock(Integer id) {
        User user = require(id);
        if (!user.isLocked() && user.getFailedLoginAttempts() == 0) {
            throw new ConflictException("That account is not locked.");
        }
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        activity.recordBy(ActivityAction.ACCOUNT_UNLOCKED, user.getUsername(), actor(), null);
        return UserSummaryDTO.from(users.save(user));
    }

    /** Ends every session for the account without changing its password. */
    @Transactional
    public void revokeSessions(Integer id) {
        User user = require(id);
        user.setSessionsValidFrom(Instant.now());
        users.save(user);
        activity.recordBy(ActivityAction.SESSIONS_REVOKED, user.getUsername(), actor(), null);
    }

    /**
     * Deletes the account and everything it owns.
     *
     * <p>Children are removed explicitly, child-first, rather than relying on a
     * database cascade: no cascade is declared on these associations, and
     * discovering that through a foreign-key violation in production is a poor
     * way to find out. The audit trail is deliberately left behind — see
     * {@link com.lokesh_codes.expense_tracker_backend.entity.ActivityLog}.
     */
    @Transactional
    public void delete(Integer id) {
        User user = require(id);
        guardDeletion(user);

        String username = user.getUsername();
        Role wasRole = user.getRole();

        transactions.deleteByUser_Id(id);
        recurring.deleteByUser_Id(id);
        budgets.deleteByUser_Id(id);
        categories.deleteByUser_Id(id);
        users.delete(user);

        activity.recordBy(ActivityAction.ACCOUNT_DELETED, username, actor(), "Role was " + wasRole);
    }

    // ----------------------------------------------------------------- guards

    /**
     * The instance must keep at least one administrator, and an administrator must
     * not be able to lock themselves out by accident. Recovering from either means
     * editing the database by hand, which on a hosted free tier is a bad
     * afternoon.
     */
    private void guardRoleChange(User user, Role target) {
        if (isSelf(user)) {
            throw new ConflictException(
                    "You cannot change your own role. Ask another administrator to do it.");
        }
        if (user.isAdmin() && target != Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) {
            throw new ConflictException("This is the only administrator. Promote someone else first.");
        }
    }

    private void guardDeactivation(User user, boolean active) {
        if (active) {
            return;
        }
        if (isSelf(user)) {
            throw new ConflictException("You cannot suspend your own account.");
        }
        if (user.isAdmin() && activeAdminCount() <= 1) {
            throw new ConflictException(
                    "This is the only active administrator. Promote someone else first.");
        }
    }

    private void guardDeletion(User user) {
        if (isSelf(user)) {
            throw new ConflictException(
                    "You cannot delete your own account from here. Ask another administrator.");
        }
        if (user.isAdmin() && users.countByRole(Role.ADMIN) <= 1) {
            throw new ConflictException("This is the only administrator. Promote someone else first.");
        }
    }

    private long activeAdminCount() {
        return users.findByRole(Role.ADMIN).stream().filter(User::isActive).count();
    }

    private boolean isSelf(User user) {
        return user.getId().equals(currentUser.requireId());
    }

    private User require(Integer id) {
        return users.findById(id)
                .orElseThrow(() -> new NotFoundException("No account with id " + id));
    }

    private String actor() {
        return currentUser.require().getUsername();
    }

    /** Blank and absent mean the same thing for an optional field. */
    private String normaliseEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
