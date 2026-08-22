package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The audit trail: what reaches it, and who may read it. */
class ActivityLogApiTest extends ApiTestBase {

    @Test
    void registrationAndSignInAreRecorded() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(get("/api/admin/activity").param("username", "mia")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.content[0].label").value("Signed in"))
                .andExpect(jsonPath("$.content[0].adverse").value(false))
                .andExpect(jsonPath("$.content[1].action").value("REGISTERED"));
    }

    /**
     * A failed attempt against a username that does not exist still has to be
     * recorded. Those rows are what a password-guessing run looks like, and an
     * audit trail that only logs successful sign-ins would not show one.
     */
    @Test
    void failedSignInAgainstAnUnknownUsernameIsRecorded() throws Exception {
        String admin = signUpAdmin("root");

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"ghost","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/activity").param("username", "ghost")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.content[0].adverse").value(true))
                .andExpect(jsonPath("$.content[0].detail").value("No such account"));
    }

    @Test
    void administrativeChangesRecordWhoMadeThem() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(jsonBody(patch("/api/admin/users/" + userIdOf("mia"))
                .header("Authorization", bearer(admin)), """
                {"role":"ADMIN","email":"changed@example.com"}
                """)).andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/admin/activity").param("username", "mia")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var actions = parse(body).get("content").findValuesAsText("action");
        assertThat(actions).contains("ROLE_CHANGED", "EMAIL_CHANGED");

        // Both attributed to the administrator who made them, not to the account
        // they happened to.
        for (var entry : parse(body).get("content")) {
            if (entry.get("action").asText().equals("ROLE_CHANGED")) {
                assertThat(entry.get("actor").asText()).isEqualTo("root");
                assertThat(entry.get("detail").asText()).isEqualTo("MEMBER to ADMIN");
            }
        }
    }

    /** An account acting on itself has no actor: nobody did it to them. */
    @Test
    void selfServiceChangesHaveNoActor() throws Exception {
        String member = signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(jsonBody(post("/api/account/password")
                .header("Authorization", bearer(member)), """
                {"currentPassword":"%s","newPassword":"BrandNewPass1!"}
                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/activity")
                .param("username", "mia")
                .param("action", "PASSWORD_CHANGED")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void adverseOnlyNarrowsToProblems() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"wrong"}
                """)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/activity").param("adverseOnly", "true")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_FAILED"));
    }

    /**
     * The upper bound is inclusive of its whole day. A range that silently excludes
     * its final date is the kind of thing nobody notices until an event is missing.
     */
    @Test
    void dateRangeIncludesBothEndDays() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");
        String today = LocalDate.now().toString();

        mockMvc.perform(get("/api/admin/activity")
                .param("from", today)
                .param("to", today)
                .param("username", "mia")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/activity")
                .param("from", LocalDate.now().plusDays(1).toString())
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void unknownActionIsRejectedRatherThanIgnored() throws Exception {
        String admin = signUpAdmin("root");

        mockMvc.perform(get("/api/admin/activity").param("action", "NOT_A_THING")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportIsCsvWithAHeaderRow() throws Exception {
        signUp("mia");
        String admin = signUpAdmin("root");

        String csv = mockMvc.perform(get("/api/admin/activity/export")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith("When,Event,Account,Performed by,Detail,IP address,Client\n");
        assertThat(csv).contains("Signed in,mia");
        assertThat(csv).contains("Registered,mia");
    }

    @Test
    void membersCannotReadTheLog() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(get("/api/admin/activity").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/activity/export").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }
}
