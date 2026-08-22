package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

/**
 * Sign-in tracking and lockout.
 *
 * <p>{@code app.security.max-failed-attempts} is 3 in the test profile, so the
 * threshold is reachable without a dozen requests.
 */
class SignInTrackingApiTest extends ApiTestBase {

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void successfulSignInIsRecordedOnTheAccount() throws Exception {
        signUp("mia");

        var user = userNamed("mia");
        assertThat(user.getLoginCount()).isEqualTo(1);
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getFailedLoginAttempts()).isZero();

        tokenFor("mia");
        assertThat(userNamed("mia").getLoginCount()).isEqualTo(2);
    }

    @Test
    void failedSignInIsCounted() throws Exception {
        signUp("mia");

        wrongPassword("mia").andExpect(status().isUnauthorized());
        assertThat(userNamed("mia").getFailedLoginAttempts()).isEqualTo(1);

        wrongPassword("mia").andExpect(status().isUnauthorized());
        assertThat(userNamed("mia").getFailedLoginAttempts()).isEqualTo(2);
    }

    /**
     * The counter resets on success. Without that, wrong passwords accumulated
     * over months would eventually lock an account that has been signing in
     * successfully the whole time.
     */
    @Test
    void successClearsTheFailureCounter() throws Exception {
        signUp("mia");

        wrongPassword("mia").andExpect(status().isUnauthorized());
        wrongPassword("mia").andExpect(status().isUnauthorized());
        assertThat(userNamed("mia").getFailedLoginAttempts()).isEqualTo(2);

        tokenFor("mia");
        assertThat(userNamed("mia").getFailedLoginAttempts()).isZero();
    }

    @Test
    void tooManyFailuresLockTheAccount() throws Exception {
        signUp("mia");

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            wrongPassword("mia").andExpect(status().isUnauthorized());
        }

        // The attempt that reaches the threshold reports the lock rather than
        // another indistinguishable 401.
        wrongPassword("mia").andExpect(status().isLocked());

        assertThat(userNamed("mia").isLocked()).isTrue();

        // And the correct password does not get in either — that is the point.
        String body = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isLocked())
                .andReturn().getResponse().getContentAsString();

        // Rounded up, not truncated. A fresh 15-minute lock has 14m59s left, and
        // "14 minutes" sends someone back a minute early to be refused again.
        assertThat(parse(body).get("message").asText()).contains("15 minutes");
    }

    @Test
    void lockedAccountCannotUseAnExistingToken() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            wrongPassword("mia");
        }

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorCanUnlockAnAccountEarly() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            wrongPassword("mia");
        }

        mockMvc.perform(get("/api/admin/users").param("status", "LOCKED")
                .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("mia"));

        mockMvc.perform(post("/api/admin/users/" + userIdOf("mia") + "/unlock")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void unlockingAnAccountThatIsNotLockedIsRefused() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(post("/api/admin/users/" + userIdOf("mia") + "/unlock")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    /**
     * A lock is a time-limited state, not a stored flag, so one whose moment has
     * passed must simply stop applying.
     */
    @Test
    void anExpiredLockLetsTheAccountBackIn() throws Exception {
        signUp("mia");

        var user = userNamed("mia");
        user.setLockedUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
        user.setFailedLoginAttempts(MAX_ATTEMPTS);
        users.save(user);

        assertThat(userNamed("mia").isLocked()).isFalse();

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    /** An unknown username must not be distinguishable from a wrong password. */
    @Test
    void unknownUsernameLooksTheSameAsAWrongPassword() throws Exception {
        signUp("mia");

        String unknown = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"nobody","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrong = wrongPassword("mia")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(parse(unknown).get("message").asText())
                .isEqualTo(parse(wrong).get("message").asText());
    }

    @Test
    void administratorCanEndSessionsWithoutChangingThePassword() throws Exception {
        String member = signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(post("/api/admin/users/" + userIdOf("mia") + "/revoke-sessions")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                .andExpect(status().isUnauthorized());

        // The password still works; only the issued token was ended.
        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    /** A username with stray whitespace is a paste, not a different account. */
    @Test
    void surroundingWhitespaceInAUsernameIsIgnored() throws Exception {
        signUp("mia");

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"  mia  ","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions wrongPassword(String username)
            throws Exception {
        return mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"%s","password":"definitely-not-it"}
                """.formatted(username)));
    }
}
