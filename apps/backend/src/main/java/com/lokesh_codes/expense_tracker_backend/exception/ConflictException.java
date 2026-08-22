package com.lokesh_codes.expense_tracker_backend.exception;

/** Raised when a request collides with existing data, such as a duplicate name. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
