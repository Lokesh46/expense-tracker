package com.lokesh_codes.expense_tracker_backend.security;

import jakarta.validation.constraints.NotBlank;

public record JwtTokenRequest(
        @NotBlank(message = "Username is required") String username,
        @NotBlank(message = "Password is required") String password) {
}
