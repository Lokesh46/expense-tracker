package com.lokesh_codes.expense_tracker_backend.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 30, message = "Username must be between 3 and 30 characters")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String password,

        @Email(message = "Enter a valid email address")
        String email,

        // Accepted for completeness but ignored: a caller must not be able to
        // register themselves as an administrator or pre-disabled.
        String role,
        Boolean active) {
}
