package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An administrator creating an account for someone.
 *
 * <p>Unlike {@code /register}, {@code role} is honoured here — that is the whole
 * point of the endpoint — which is why it lives behind {@code ROLE_ADMIN} and
 * the public registration path still hard-codes {@link Role#MEMBER}.
 */
public record CreateUserRequest(

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 30, message = "Username must be between 3 and 30 characters")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String password,

        @Email(message = "Enter a valid email address")
        @Size(max = 120, message = "That email address is too long")
        String email,

        @NotNull(message = "Choose a role")
        Role role,

        /** Defaults to an active account; an administrator can create one switched off. */
        Boolean active) {
}
