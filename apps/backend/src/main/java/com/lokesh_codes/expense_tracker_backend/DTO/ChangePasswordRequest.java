package com.lokesh_codes.expense_tracker_backend.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing your own password.
 *
 * <p>The current password is required even though the request is already
 * authenticated: a token left behind on a shared machine should not be enough to
 * take the account over permanently.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Enter your current password")
        String currentPassword,

        @NotBlank(message = "Enter a new password")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String newPassword) {
}
