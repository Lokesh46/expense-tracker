package com.lokesh_codes.expense_tracker_backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

@Service
public class JwtTokenService {

    /** Millisecond-precision companion to the standard, second-precision "iat". */
    public static final String ISSUED_AT_MILLIS = "iat_ms";

    private final JwtEncoder jwtEncoder;
    private final long ttlMinutes;

    public JwtTokenService(JwtEncoder jwtEncoder,
            @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        this.jwtEncoder = jwtEncoder;
        this.ttlMinutes = ttlMinutes;
    }

    public String generateToken(Authentication authentication) {

        var scope = authentication
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));

        var issuedAt = Instant.now();

        var claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttlMinutes, ChronoUnit.MINUTES))
                .subject(authentication.getName())
                .claim("scope", scope)
                // The standard "iat" claim is whole seconds, which is too coarse to
                // decide whether this token was issued before or after a revocation
                // that happened in the same second. Changing a password revokes and
                // then immediately reissues, so that second is exactly the one that
                // matters. See AccountStateFilter.
                .claim(ISSUED_AT_MILLIS, issuedAt.toEpochMilli())
                .build();

        return this.jwtEncoder
                .encode(JwtEncoderParameters.from(claims))
                .getTokenValue();
    }
}