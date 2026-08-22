package com.lokesh_codes.expense_tracker_backend.controller;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.AccountDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ActivityFilter;
import com.lokesh_codes.expense_tracker_backend.DTO.ActivityLogDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ChangePasswordRequest;
import com.lokesh_codes.expense_tracker_backend.DTO.PageResponse;
import com.lokesh_codes.expense_tracker_backend.DTO.UpdateEmailRequest;
import com.lokesh_codes.expense_tracker_backend.service.AccountService;

import jakarta.validation.Valid;

/**
 * Your own account, whatever your role.
 *
 * <p>No id appears in any path here. The account is whoever the token says it is,
 * which removes the class of bug where an id from the request reaches a query
 * without an ownership check.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private static final Set<String> SORTABLE = Set.of("occurredAt", "action");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "occurredAt");

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * The authoritative answer to "who am I and what may I do".
     *
     * <p>The frontend can read a role from the token without a round trip, and
     * does, to decide what to render. It asks here as well because a token is a
     * claim rather than an authority: an administrator may have changed something
     * since it was issued.
     */
    @GetMapping("/me")
    public ResponseEntity<AccountDTO> me() {
        return ResponseEntity.ok(accountService.me());
    }

    @PutMapping("/email")
    public ResponseEntity<AccountDTO> updateEmail(@Valid @RequestBody UpdateEmailRequest request) {
        return ResponseEntity.ok(accountService.updateEmail(request.email()));
    }

    /**
     * Changes your own password. Every session ends, including this one, so the
     * client should expect its next request to be refused and sign in again.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * Your own sign-in history.
     *
     * <p>Available to members, not only administrators. Noticing a sign-in from an
     * address you do not recognise is the earliest warning that a password has
     * leaked, and it should not depend on somebody else looking.
     */
    @GetMapping("/activity")
    public ResponseEntity<PageResponse<ActivityLogDTO>> myActivity(
            @PageableDefault(size = 20) Pageable pageable) {

        var page = accountService.myActivity(
                new ActivityFilter(null, null, null, null, null),
                PageableSupport.sanitise(pageable, SORTABLE, DEFAULT_SORT));

        return ResponseEntity.ok(PageResponse.from(page, ActivityLogDTO::from));
    }
}
