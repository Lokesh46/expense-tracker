package com.lokesh_codes.expense_tracker_backend.exception;

/**
 * A caller has run a bulk endpoint more often than the allowance permits.
 *
 * <p>Distinct from {@link ConflictException} on purpose. A 429 tells the client
 * to wait and try again, and the frontend can say so; a 409 tells it something
 * about the request was wrong, which would send the user looking for a fault in
 * their file that is not there.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
