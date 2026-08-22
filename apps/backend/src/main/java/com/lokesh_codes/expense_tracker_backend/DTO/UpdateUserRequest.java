package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * A partial update: null means "leave this alone".
 *
 * <p>Sending the whole object back would make two administrators editing
 * different fields overwrite each other, and would make clearing an email
 * indistinguishable from not touching it. Clearing an email is done with an
 * empty string, which is distinguishable from null.
 */
public record UpdateUserRequest(

        @Email(message = "Enter a valid email address")
        @Size(max = 120, message = "That email address is too long")
        String email,

        Role role,

        Boolean active) {
}
