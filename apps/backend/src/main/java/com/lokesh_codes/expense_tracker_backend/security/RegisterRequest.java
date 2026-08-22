package com.lokesh_codes.expense_tracker_backend.security;

public record RegisterRequest(String username, String password, String email, String role, Boolean active) {
}
