package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.service.AdminPromotion;

/**
 * Appointing the first administrator from configuration.
 *
 * <p>Runs in its own context, because {@code ADMIN_USERNAME} is read once at
 * startup and the rest of the suite deliberately leaves it empty so that every
 * other test's starting state is visible in the test itself.
 */
@TestPropertySource(properties = "app.admin.username=the-founder")
class AdminPromotionApiTest extends ApiTestBase {

    @Autowired
    private AdminPromotion adminPromotion;

    /**
     * The point of promoting at registration rather than only at startup: without
     * it, appointing the first administrator means register, set the variable,
     * redeploy, and sign in again — to get a role that was always intended.
     */
    @Test
    void theConfiguredUsernameIsAnAdministratorAsSoonAsItRegisters() throws Exception {
        String token = signUp("the-founder");

        assertThat(userNamed("the-founder").getRole()).isEqualTo(Role.ADMIN);

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void registrationSaysSoWhenItPromoted() throws Exception {
        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"the-founder","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Administrator account created. You can sign in now."));
    }

    /** Everybody else registers as a member, exactly as before. */
    @Test
    void everyOtherRegistrationIsStillAMember() throws Exception {
        String token = signUp("mia");

        assertThat(userNamed("mia").getRole()).isEqualTo(Role.MEMBER);

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    /**
     * A username typed into a registration form is not reliably the same case as
     * one typed into a hosting dashboard, and "the promotion silently did not
     * happen" is a miserable thing to debug from a 403.
     */
    @Test
    void theMatchIgnoresCase() throws Exception {
        signUp("The-Founder");
        assertThat(userNamed("The-Founder").getRole()).isEqualTo(Role.ADMIN);
    }

    /** The promotion is recorded, attributed to configuration rather than a person. */
    @Test
    void thePromotionIsWrittenToTheActivityLog() throws Exception {
        String token = signUp("the-founder");

        mockMvc.perform(get("/api/admin/activity")
                .param("username", "the-founder")
                .param("action", "ROLE_CHANGED")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actor").value("system"))
                .andExpect(jsonPath("$.content[0].detail").value("MEMBER to ADMIN via ADMIN_USERNAME"));
    }

    /**
     * Idempotent, which is what makes it safe to leave ADMIN_USERNAME set. Every
     * restart runs the startup path again; if that re-applied, the audit trail
     * would collect a promotion per deploy for an account whose role never moved.
     */
    @Test
    void reapplyingToAnExistingAdministratorDoesNothing() throws Exception {
        String token = signUp("the-founder");

        assertThat(adminPromotion.apply(userNamed("the-founder"), "second run")).isFalse();

        mockMvc.perform(get("/api/admin/activity")
                .param("username", "the-founder")
                .param("action", "ROLE_CHANGED")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** An unconfigured name is never promoted, however it is spelled. */
    @Test
    void aDifferentUsernameIsNeverPromoted() {
        signUpQuietly("someone-else");
        assertThat(adminPromotion.matches("someone-else")).isFalse();
        assertThat(adminPromotion.apply(userNamed("someone-else"), "manual")).isFalse();
        assertThat(userNamed("someone-else").getRole()).isEqualTo(Role.MEMBER);
    }

    private void signUpQuietly(String username) {
        try {
            signUp(username);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
