package com.lokesh_codes.expense_tracker_backend.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns exceptions into a consistent JSON body.
 *
 * <p>Without this, a missing record surfaced as a 500 with a stack trace, which
 * the frontend could only report as "something went wrong".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Credentials that do not match, and unknown usernames, look identical to the caller. */
    @ExceptionHandler({ BadCredentialsException.class, UsernameNotFoundException.class })
    public ResponseEntity<Map<String, Object>> handleBadCredentials(Exception e) {
        return body(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        var payload = base(HttpStatus.BAD_REQUEST, "Some fields need attention");
        payload.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    /**
     * A method-security denial, from a {@code @PreAuthorize} inside a service.
     *
     * <p>Denials raised in the filter chain are handled by Spring Security's own
     * entry point, but one raised past it propagates out of the controller and,
     * without this, is reported as a 500 — turning "you are not an administrator"
     * into "the server is broken".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "You do not have permission to do that.");
    }

    /**
     * A body Jackson could not read: malformed JSON, or a value outside an enum
     * such as a role of "SUPERUSER". Reported as the client error it is; it used
     * to surface as a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "That request body could not be read. Check the values sent.");
    }

    /** A path or query value of the wrong type, e.g. {@code /users/abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "'" + e.getName() + "' is not a valid value.");
    }

    /**
     * A request to a path nothing is mapped to.
     *
     * <p>This is a 404, and it used to be a 500 with a full stack trace in the log.
     * The API serves no static content, so an unmatched path reaches the resource
     * handler and raises {@link NoResourceFoundException}, which the catch-all below
     * then reported as a server fault. Forty of them arrived in a minute the first
     * time anything polled the root URL.
     *
     * <p>Only paths the security chain admits get this far: {@code /} and the other
     * permitted paths, and anything under {@code /api} for a caller holding a token.
     * An unauthenticated request to an unmapped path is still a 401, which is the
     * right answer — whether it exists is not something an anonymous caller gets to
     * find out.
     *
     * <p>Logged at debug, not error: an unmatched path says nothing about the health
     * of the service, and at error level it drowns out the things that do.
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<Map<String, Object>> handleNoHandler(Exception e) {
        log.debug("No handler for request: {}", e.getMessage());
        return body(HttpStatus.NOT_FOUND, "No such endpoint.");
    }

    /**
     * The right path, the wrong verb — a POST to something that only answers GET.
     *
     * <p>Same defect as above: a client error reported as a server one. 405 also
     * has to name the verbs that would work, which is what the header is for.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {

        var supported = e.getSupportedHttpMethods();
        var builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(HttpMethod[]::new));
        }

        return builder.body(base(HttpStatus.METHOD_NOT_ALLOWED,
                e.getMethod() + " is not supported here."));
    }

    /** A required query parameter that was not sent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "'" + e.getParameterName() + "' is required.");
    }

    /**
     * A multipart request whose file part is absent.
     *
     * <p>Distinct from the case below, where the request is not multipart at all.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(
            MissingServletRequestPartException e) {
        return body(HttpStatus.BAD_REQUEST, "No '" + e.getRequestPartName() + "' was attached.");
    }

    /**
     * A body in a format the endpoint does not accept.
     *
     * <p>415, not 400 and certainly not 500. This is what posting to the CSV import
     * endpoint without a multipart body actually raises, and it read as a server
     * fault until now -- so "upload failed" gave no hint that the upload was
     * malformed rather than the server broken.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e) {

        var supported = e.getSupportedMediaTypes();
        String expected = supported.isEmpty() ? "a different format" : supported.get(0).toString();

        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This endpoint expects " + expected + ".");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    /** Anything unhandled is logged in full but reported without internals. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(base(status, message));
    }

    private Map<String, Object> base(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("error", status.getReasonPhrase());
        payload.put("message", message == null ? status.getReasonPhrase() : message);
        return payload;
    }
}
