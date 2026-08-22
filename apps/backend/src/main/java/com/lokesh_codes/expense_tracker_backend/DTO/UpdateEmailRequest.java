package com.lokesh_codes.expense_tracker_backend.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Changing your own email. An empty value removes it. */
public record UpdateEmailRequest(

        @Email(message = "Enter a valid email address")
        @Size(max = 120, message = "That email address is too long")
        String email) {
}
