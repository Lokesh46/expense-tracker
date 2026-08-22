package com.lokesh_codes.expense_tracker_backend.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> origins = Arrays.stream(frontendUrls.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();

                registry.addMapping("/**")
                        .allowedOriginPatterns(origins.toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
