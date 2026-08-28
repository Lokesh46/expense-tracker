package com.lokesh_codes.expense_tracker_backend.security;

import static org.springframework.security.config.Customizer.withDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class JwtSecurityConfig {

        /**
         * Whether the H2 console is served at all. The console's path used to be
         * permitted unconditionally, which meant the only thing standing between
         * the internet and a database browser was a single property in the
         * production profile. Tying the path rule to the same switch that enables
         * the console removes that gap.
         */
        @Value("${spring.h2.console.enabled:false}")
        private boolean h2ConsoleEnabled;

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
                        AccountStateFilter accountStateFilter) throws Exception {
                httpSecurity
                                .csrf(AbstractHttpConfigurer::disable) // (1)
                                .sessionManagement(
                                                session -> session.sessionCreationPolicy(
                                                                SessionCreationPolicy.STATELESS)) // (2)
                                .authorizeHttpRequests(
                                                auth -> {
                                                        auth.requestMatchers("/", // #CHANGE
                                                                        "/authenticate", "/actuator", "/actuator/*",
                                                                        "/register")
                                                                        .permitAll()
                                                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                                                        .permitAll();

                                                        if (h2ConsoleEnabled) {
                                                                auth.requestMatchers("/h2-console/**").permitAll();
                                                        }

                                                        // Administration is gated here as well as on
                                                        // every service method. A path rule alone is
                                                        // one typo away from being open, and typos in
                                                        // path rules do not fail loudly.
                                                        auth.requestMatchers("/api/admin/**")
                                                                        .hasRole("ADMIN")
                                                                        .anyRequest()
                                                                        .authenticated(); // (3)
                                                })
                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults())) // (4)
                                // Runs once the token has been validated, and checks it against
                                // the account it names. See AccountStateFilter.
                                .addFilterAfter(accountStateFilter, BearerTokenAuthenticationFilter.class)
                                .exceptionHandling(
                                                (ex) -> ex.authenticationEntryPoint(
                                                                new BearerTokenAuthenticationEntryPoint())
                                                                .accessDeniedHandler(
                                                                                new BearerTokenAccessDeniedHandler()))
                                .headers(headers -> {
                                        // Only the dev console is framed, and only when it is served.
                                        if (h2ConsoleEnabled) {
                                                headers.frameOptions(frame -> frame.sameOrigin());
                                        } else {
                                                headers.frameOptions(frame -> frame.deny());
                                        }

                                        headers
                                                        // This API answers JSON and CSV. Nothing it
                                                        // returns should ever be interpreted as
                                                        // script, and a CSV export rendered inline
                                                        // instead of downloaded is exactly how a
                                                        // planted description becomes stored XSS.
                                                        .contentTypeOptions(withDefaults())
                                                        .httpStrictTransportSecurity(hsts -> hsts
                                                                        .includeSubDomains(true)
                                                                        .maxAgeInSeconds(31_536_000))
                                                        .referrerPolicy(referrer -> referrer.policy(
                                                                        ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                                                        // An API serves no documents, so nothing it
                                                        // returns has any legitimate need to load a
                                                        // subresource.
                                                        .contentSecurityPolicy(csp -> csp
                                                                        .policyDirectives(
                                                                                        "default-src 'none'; frame-ancestors 'none'"));
                                });

                return httpSecurity.build();
        }

        @Bean
        public AuthenticationManager authenticationManager(
                        UserDetailsService userDetailsService,
                        PasswordEncoder passwordEncoder) {
                var authenticationProvider = new DaoAuthenticationProvider();
                authenticationProvider.setUserDetailsService(userDetailsService);
                // Without this the provider falls back to the delegating encoder,
                // which cannot read the bare BCrypt hashes stored by /register.
                authenticationProvider.setPasswordEncoder(passwordEncoder);
                return new ProviderManager(authenticationProvider);
        }

        @Bean
        public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
                JWKSet jwkSet = new JWKSet(rsaKey);
                return (((jwkSelector, securityContext) -> jwkSelector.select(jwkSet)));
        }

        @Bean
        JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
                return new NimbusJwtEncoder(jwkSource);
        }

        @Bean
        JwtDecoder jwtDecoder(RSAKey rsaKey) throws JOSEException {
                return NimbusJwtDecoder
                                .withPublicKey(rsaKey.toRSAPublicKey())
                                .build();
        }

        /**
         * The signing key is loaded from disk rather than generated per boot, so
         * tokens stay valid across restarts. See {@link JwtKeyProvider}.
         */
        @Bean
        public RSAKey rsaKey(JwtKeyProvider keyProvider) {
                return keyProvider.loadOrCreate();
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

}
