package com.lokesh_codes.expense_tracker_backend.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;
import com.lokesh_codes.expense_tracker_backend.service.ActivityLogService;
import com.lokesh_codes.expense_tracker_backend.service.AdminPromotion;
import com.lokesh_codes.expense_tracker_backend.service.CategoryService;
import com.lokesh_codes.expense_tracker_backend.service.SignInService;

import jakarta.validation.Valid;

@RestController
public class JwtAuthenticationController {

    private final JwtTokenService tokenService;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CategoryService categoryService;
    private final SignInService signInService;
    private final ActivityLogService activityLog;
    private final AdminPromotion adminPromotion;

    public JwtAuthenticationController(JwtTokenService tokenService,
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CategoryService categoryService,
            SignInService signInService,
            ActivityLogService activityLog,
            AdminPromotion adminPromotion) {
        this.tokenService = tokenService;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.categoryService = categoryService;
        this.signInService = signInService;
        this.activityLog = activityLog;
        this.adminPromotion = adminPromotion;
    }

    /**
     * Exchanges credentials for a token, and records the attempt either way.
     *
     * <p>Deliberately not {@code @Transactional}. Both outcomes write to the
     * database through {@link SignInService}, and a failed attempt must commit
     * even though this method then throws — a lockout counter that rolls back
     * with the exception that incremented it never reaches its limit.
     */
    @PostMapping("/authenticate")
    public ResponseEntity<JwtTokenResponse> generateToken(@Valid @RequestBody JwtTokenRequest request) {
        // Trimmed because a username pasted from a password manager often arrives
        // with a trailing space, and "wrong password" is a poor explanation for it.
        String username = request.username().trim();

        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));

            signInService.recordSuccess(username);
            return ResponseEntity.ok(new JwtTokenResponse(tokenService.generateToken(authentication)));

        } catch (LockedException e) {
            // Spring's pre-authentication checks run before the password is
            // compared, so a locked account never reaches the counter.
            throw locked(signInService.remainingLockout(username));

        } catch (DisabledException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account has been suspended. Contact an administrator.");

        } catch (AuthenticationException e) {
            Duration lockedFor = signInService.recordFailure(username);
            if (lockedFor != null) {
                throw locked(lockedFor);
            }
            // A wrong password and an unknown username are indistinguishable to
            // the caller; the global handler turns both into the same 401.
            throw e;
        }
    }

    /**
     * Creates an account and seeds it with starter categories, so a new user can
     * record an expense straight away instead of landing on empty screens.
     */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<Map<String, String>> registerUser(@Valid @RequestBody RegisterRequest request) {
        String username = request.username().trim();

        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("That username is already taken.");
        }

        String email = request.email() == null ? null : request.email().trim();
        if (email != null && !email.isBlank() && userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("That email address is already registered.");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        // Role and active status are set by the server, never taken from the
        // request. A caller cannot register themselves as an administrator or
        // pre-disabled; the only thing that can raise the role is ADMIN_USERNAME,
        // applied below and set by whoever runs the deployment.
        user.setRole(Role.MEMBER);
        user.setActive(true);

        User saved = userRepository.save(user);
        categoryService.seedDefaultsFor(saved);
        activityLog.record(ActivityAction.REGISTERED, saved.getUsername(), null);

        // The one exception to "self-registration always produces a member", and it
        // is configuration rather than the request that decides: ADMIN_USERNAME is
        // set by whoever runs the deployment, and cannot be influenced by anything
        // in the body. Applied here as well as at startup so appointing the first
        // administrator does not need a restart. See AdminPromotion.
        boolean promoted = adminPromotion.apply(saved, "on registration");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", promoted
                        ? "Administrator account created. You can sign in now."
                        : "Account created. You can sign in now."));
    }

    /**
     * 423 rather than 401, and specific about why.
     *
     * <p>Saying "locked" confirms the account exists. That disclosure is accepted
     * because registration already makes the same one — it refuses a taken
     * username by saying it is taken — and because five "wrong password" replies
     * to a correct password is how someone concludes the application is broken.
     */
    private ResponseStatusException locked(Duration remaining) {
        return new ResponseStatusException(HttpStatus.LOCKED,
                "Too many failed attempts. This account is locked for "
                        + SignInService.describe(remaining) + ".");
    }
}
