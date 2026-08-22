package com.lokesh_codes.expense_tracker_backend.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An administrator setting someone else's password. */
public record SetPasswordRequest(

        @NotBlank(message = "A new password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String newPassword) {
}
