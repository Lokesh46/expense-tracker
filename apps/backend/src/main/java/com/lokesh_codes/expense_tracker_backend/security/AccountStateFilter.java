package com.lokesh_codes.expense_tracker_backend.security;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checks a valid token against the account it belongs to, on every request.
 *
 * <p>A signed JWT is a statement about the past. It says who signed in and what
 * they could do at that moment, and it keeps saying so until it expires. That is
 * the point of stateless authentication, and it is also what makes a user
 * management screen a lie without this filter: suspending an account, demoting an
 * administrator or resetting a password would all take effect up to ninety
 * minutes later, whenever the token happened to run out.
 *
 * <p>So four things are re-checked against the database:
 *
 * <ul>
 *   <li>the account still exists — a deleted user's token must stop working;
 *   <li>it has not been suspended;
 *   <li>it is not locked out;
 *   <li>the token was issued after {@code sessionsValidFrom}, which is stamped
 *       whenever a password changes or an administrator revokes access.
 * </ul>
 *
 * <p>The authorities are then rebuilt from the stored role rather than read from
 * the token's {@code scope} claim, so a promotion or demotion applies to the very
 * next request.
 *
 * <p>The cost is one lookup by an indexed unique column per authenticated
 * request. That is a real cost and it is accepted knowingly: most requests
 * already load the same row through {@code CurrentUserService}, and the
 * alternative — a token that outlives the authority it describes — is not a
 * trade-off worth making in a feature whose whole purpose is control over
 * accounts.
 */
@Component
public class AccountStateFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public AccountStateFilter(UserRepository users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();

        // Anonymous requests to public endpoints have nothing to re-check.
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }

        Jwt token = jwtAuthentication.getToken();
        var found = users.findByUsername(jwtAuthentication.getName());

        if (found.isEmpty()) {
            reject(response, "This account no longer exists. Please sign in again.");
            return;
        }

        User user = found.get();

        if (!user.isActive()) {
            reject(response, "This account has been suspended.");
            return;
        }
        if (user.isLocked()) {
            reject(response, "This account is temporarily locked. Try again shortly.");
            return;
        }
        if (issuedBeforeRevocation(token, user)) {
            reject(response, "Your session has ended. Please sign in again.");
            return;
        }

        // Authority from the database, not from the token, so a role change is
        // effective immediately rather than at expiry.
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().authority()));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token, authorities, user.getUsername()));

        chain.doFilter(request, response);
    }

    /**
     * Whether this token predates the account's last revocation.
     *
     * <p>Compared in milliseconds, from the {@code iat_ms} claim, because the
     * standard {@code iat} is whole seconds and a second is not fine enough here.
     * Changing a password revokes every session and the user immediately signs in
     * again; with second resolution the new token's {@code iat} rounds down below
     * the revocation it came after, and the user is thrown out of the session they
     * just created. Rounding the other way instead would leave a revoked token
     * working for up to a second, which is the wrong error to prefer in the one
     * check whose job is ending access.
     *
     * <p>A token without the claim was issued by an older build. It is compared on
     * whole seconds and given the benefit of the doubt within its second; those
     * tokens expire within the hour and cannot be reissued.
     */
    private boolean issuedBeforeRevocation(Jwt token, User user) {
        Instant revokedAt = user.getSessionsValidFrom();
        if (revokedAt == null) {
            return false;
        }

        Object issuedAtMillis = token.getClaim(JwtTokenService.ISSUED_AT_MILLIS);
        if (issuedAtMillis instanceof Number millis) {
            return millis.longValue() < revokedAt.toEpochMilli();
        }

        Instant issuedAt = token.getIssuedAt();
        if (issuedAt == null) {
            // Not issued by this application, or by a build that set neither claim,
            // and so cannot be shown to postdate the revocation.
            return true;
        }
        return issuedAt.isBefore(revokedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 401 with a reason, in the same shape as every other error from this API.
     *
     * <p>Unlike a sign-in failure, being specific here leaks nothing: the caller
     * already holds a valid token for the account, so they know it exists. What
     * they do not know is why it stopped working, and "unauthorised" with no
     * explanation is how a suspended user concludes the application is broken.
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        SecurityContextHolder.clearContext();

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.put("message", message);

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
