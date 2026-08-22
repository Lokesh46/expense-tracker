package com.lokesh_codes.expense_tracker_backend;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * What an account can do to itself.
 *
 * <p>No id appears in any of these paths, so the tests that matter most are the
 * ones proving one account cannot reach another's history through them.
 */
class AccountApiTest extends ApiTestBase {

    @Test
    void meReportsTheAuthenticatedAccount() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mia"))
                .andExpect(jsonPath("$.email").value("mia@example.com"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.loginCount").value(1));
    }

    @Test
    void meReportsAnAdministratorAsOne() throws Exception {
        String admin = signUpAdmin("root");

        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /**
     * A promotion has to show up here without a fresh sign-in, because this is
     * what the UI asks to decide whether to offer the admin screens at all.
     */
    @Test
    void meReflectsARoleChangeMadeByAnAdministrator() throws Exception {
        String admin = signUpAdmin("root");
        signUp("mia");

        mockMvc.perform(jsonBody(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/admin/users/" + userIdOf("mia"))
                        .header("Authorization", bearer(admin)),
                "{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(tokenFor("mia"))))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void emailCanBeChangedAndRemoved() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(jsonBody(put("/api/account/email")
                .header("Authorization", bearer(member)), """
                {"email":"new@example.com"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));

        // Blank means "remove it", which is distinguishable from not sending it.
        mockMvc.perform(jsonBody(put("/api/account/email")
                .header("Authorization", bearer(member)), """
                {"email":""}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()));
    }

    @Test
    void anEmailAlreadyInUseIsRefused() throws Exception {
        signUp("noah");
        String member = signUp("mia");

        mockMvc.perform(jsonBody(put("/api/account/email")
                .header("Authorization", bearer(member)), """
                {"email":"noah@example.com"}
                """))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(jsonBody(put("/api/account/email")
                .header("Authorization", bearer(member)), """
                {"email":"not-an-address"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void passwordChangeRequiresTheCurrentPassword() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(jsonBody(post("/api/account/password")
                .header("Authorization", bearer(member)), """
                {"currentPassword":"wrong-one","newPassword":"BrandNewPass1!"}
                """))
                // 409 rather than 401: the request is authenticated, it is the
                // supplied password that is wrong. A 401 would make the client
                // throw away a perfectly good token.
                .andExpect(status().isConflict());

        // Unchanged, so the original still works.
        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void reusingTheSamePasswordIsRefused() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(jsonBody(post("/api/account/password")
                .header("Authorization", bearer(member)), """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(PASSWORD, PASSWORD)))
                .andExpect(status().isConflict());
    }

    /**
     * Changing a password ends every session, including the one doing the
     * changing. Deliberate: someone changing their password usually wants whoever
     * else had it to stop being signed in, and stateless tokens cannot be revoked
     * selectively.
     */
    @Test
    void passwordChangeEndsTheCurrentSession() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(jsonBody(post("/api/account/password")
                .header("Authorization", bearer(member)), """
                {"currentPassword":"%s","newPassword":"BrandNewPass1!"}
                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/account/me").header("Authorization", bearer(member)))
                .andExpect(status().isUnauthorized());

        String reissued = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"mia","password":"BrandNewPass1!"}
                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // And the token from immediately afterwards works. This is the case that
        // second-resolution timestamps get wrong.
        mockMvc.perform(get("/api/account/me")
                .header("Authorization", bearer(parse(reissued).get("token").asText())))
                .andExpect(status().isOk());
    }

    @Test
    void ownActivityIsVisibleToTheAccountItself() throws Exception {
        String member = signUp("mia");

        mockMvc.perform(get("/api/account/activity").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                // Registered, then signed in.
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.content[1].action").value("REGISTERED"));
    }

    /**
     * The username comes from the token, never the query string, so there is no
     * parameter that widens the result. This is the test for that.
     */
    @Test
    void ownActivityCannotBeWidenedToAnotherAccount() throws Exception {
        signUp("noah");
        String member = signUp("mia");

        mockMvc.perform(get("/api/account/activity")
                .param("username", "noah")
                .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].username").value("mia"))
                .andExpect(jsonPath("$.content[1].username").value("mia"));
    }

    @Test
    void unauthenticatedAccountEndpointsAreRefused() throws Exception {
        mockMvc.perform(get("/api/account/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/account/activity")).andExpect(status().isUnauthorized());
    }
}
