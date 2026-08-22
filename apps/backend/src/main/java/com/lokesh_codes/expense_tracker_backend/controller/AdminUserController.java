package com.lokesh_codes.expense_tracker_backend.controller;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.AdminStatsDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.CreateUserRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.PageResponse;
import com.lokesh_codes.expense_tracker_backend.DTO.SetPasswordRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.UpdateUserRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.UserDetailDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.UserFilter;
import com.lokesh_codes.expense_tracker_backend.DTO.UserSummaryDTO;
import com.lokesh_codes.expense_tracker_backend.entity.AccountStatus;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.service.UserAdminService;

import jakarta.validation.Valid;

/**
 * Account administration.
 *
 * <p>Restricted to {@code ROLE_ADMIN} twice over: by the path rule in
 * {@code JwtSecurityConfig} and by {@code @PreAuthorize} on every method of
 * {@link UserAdminService}.
 *
 * <p>There is no endpoint here for anybody's transactions, budgets or
 * categories, and that is the design rather than an omission. An administrator
 * manages who has an account; what they spend remains theirs.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    /**
     * Sortable columns. Note the absence of {@code password} — see
     * {@link PageableSupport}.
     */
    private static final Set<String> SORTABLE = Set.of(
            "username", "email", "role", "active", "createdAt", "lastLoginAt", "loginCount", "id");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final UserAdminService userAdminService;

    public AdminUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /** Paged, searchable list. Filtering happens in the database. */
    @GetMapping
    public ResponseEntity<PageResponse<UserSummaryDTO>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AccountStatus status,
            @PageableDefault(size = 20) Pageable pageable) {

        var filter = new UserFilter(search, role, status);
        var page = userAdminService.list(filter,
                PageableSupport.sanitise(pageable, SORTABLE, DEFAULT_SORT));

        return ResponseEntity.ok(PageResponse.from(page, UserSummaryDTO::from));
    }

    /** The numbers on the admin overview. */
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsDTO> stats() {
        return ResponseEntity.ok(userAdminService.stats());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDetailDTO> detail(@PathVariable Integer id) {
        return ResponseEntity.ok(userAdminService.detail(id));
    }

    @PostMapping
    public ResponseEntity<UserSummaryDTO> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdminService.create(request));
    }

    /**
     * PATCH rather than PUT: the body carries only the fields being changed, so
     * two administrators editing different things do not overwrite each other.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<UserSummaryDTO> update(@PathVariable Integer id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userAdminService.update(id, request));
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable Integer id,
            @Valid @RequestBody SetPasswordRequest request) {
        userAdminService.setPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Clears a lockout early, rather than waiting for it to expire. */
    @PostMapping("/{id}/unlock")
    public ResponseEntity<UserSummaryDTO> unlock(@PathVariable Integer id) {
        return ResponseEntity.ok(userAdminService.unlock(id));
    }

    /** Ends every session for the account, leaving the password alone. */
    @PostMapping("/{id}/revoke-sessions")
    public ResponseEntity<Void> revokeSessions(@PathVariable Integer id) {
        userAdminService.revokeSessions(id);
        return ResponseEntity.noContent().build();
    }

    /** Deletes the account and everything it owns. The audit trail stays. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        userAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
