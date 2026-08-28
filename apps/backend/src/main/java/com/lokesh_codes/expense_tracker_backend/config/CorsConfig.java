package com.lokesh_codes.expense_tracker_backend.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    /**
     * Allowed browser origins, comma-separated.
     *
     * <p>Accepts patterns such as {@code http://localhost:[*]}, which a plain
     * {@code allowedOrigins} list cannot express. This matters in development,
     * where the dev server forwards the browser's real {@code Origin} even
     * though the request is proxied — a mismatch there surfaces as a bare 403
     * with no explanation.
     */
    @Value("${frontend.url}")
    private String frontendUrls;

    private final Environment environment;

    public CorsConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> origins = Arrays.stream(frontendUrls.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();

                rejectWildcardInProduction(origins);

                registry.addMapping("/**")
                        .allowedOriginPatterns(origins.toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }

    /**
     * Refuses to start a production instance that would accept credentialed
     * requests from anywhere.
     *
     * <p>{@code allowCredentials(true)} combined with a wildcard origin lets any
     * page on the internet read a signed-in user's transactions using their own
     * browser session. It is the single worst value {@code FRONTEND_URL} can
     * hold, it is one careless deploy away, and it fails silently — everything
     * works, and works for everybody. Failing at boot is loud, which is the point.
     *
     * <p>Development is left alone: patterns like {@code http://localhost:[*]}
     * are what make the proxied dev server work.
     */
    private void rejectWildcardInProduction(List<String> origins) {
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "FRONTEND_URL is not set. Production must name the origins allowed to call this API.");
        }
        for (String origin : origins) {
            if (origin.equals("*") || origin.startsWith("*.") || origin.equals("http://*")
                    || origin.equals("https://*")) {
                throw new IllegalStateException(
                        "FRONTEND_URL contains the wildcard origin '" + origin
                                + "'. Credentialed requests would be accepted from any site. "
                                + "Name the frontend's origin explicitly.");
            }
        }
    }
}
