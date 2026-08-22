package com.lokesh_codes.expense_tracker_backend.exception;

/**
 * Raised when a record does not exist, or exists but belongs to another user.
 *
 * <p>Both cases deliberately produce the same 404: distinguishing them would
 * let a caller confirm that an id exists simply by probing it.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
