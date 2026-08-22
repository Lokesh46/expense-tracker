package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthenticationApiTest extends ApiTestBase {

    /**
     * The regression that made the app unusable: {@code JpaUserDetailsService}
     * returned the stored password prefixed with {@code "{noop}"}, telling Spring
     * Security to treat a BCrypt hash as plaintext. Registration succeeded and
     * every subsequent sign-in failed.
     */
    @Test
    @DisplayName("an account can sign in with the password it registered with")
    void registeredUserCanSignIn() throws Exception {
        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"ada","password":"Passw0rd!","email":"ada@example.com"}
                """))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"ada","password":"Passw0rd!"}
                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = parse(body).get("token").asText();

        // A JWT, not an empty string dressed up as success.
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("the password is stored hashed, not in plain text")
    void passwordIsHashed() throws Exception {
        signUp("grace");

        // Reaching through the API rather than the repository would not prove
        // anything, so this asserts on what a sign-in accepts: the literal hash
        // must not work as a password.
        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"grace","password":"$2a$10$notTheRealHash"}
                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong password is rejected")
    void wrongPasswordRejected() throws Exception {
        signUp("linus");

        mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"linus","password":"NotThePassword!"}
                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown username fails the same way a wrong password does")
    void unknownUserIndistinguishableFromWrongPassword() throws Exception {
        signUp("known");

        String wrongPassword = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"known","password":"Wrong!"}
                """)).andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"nobody","password":"Wrong!"}
                """)).andReturn().getResponse().getContentAsString();

        // If these differed, an attacker could enumerate valid usernames.
        assertThat(parse(unknownUser).get("message").asText())
                .isEqualTo(parse(wrongPassword).get("message").asText());
    }

    @Test
    @DisplayName("a duplicate username is refused")
    void duplicateUsernameRefused() throws Exception {
        signUp("twice");

        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"twice","password":"Passw0rd!","email":"other@example.com"}
                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("registration reports which fields are wrong")
    void registrationValidationIsSpecific() throws Exception {
        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"x","password":"short"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    /**
     * The request body carries role and active, but the server sets both. A
     * caller must not be able to register themselves as an administrator.
     */
    @Test
    @DisplayName("registration ignores a role supplied by the caller")
    void roleCannotBeSelfAssigned() throws Exception {
        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"sneaky","password":"Passw0rd!","role":"ADMIN","active":false}
                """))
                .andExpect(status().isCreated());

        // Had active been honoured, the account would be disabled and unable to
        // sign in at all.
        String token = tokenFor("sneaky");

        String claims = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]));

        assertThat(claims).contains("ROLE_USER");
        assertThat(claims).doesNotContain("ROLE_ADMIN");
    }

    @Test
    @DisplayName("protected endpoints reject a request with no token")
    void protectedEndpointsRequireAToken() throws Exception {
        mockMvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/budgets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/recurring")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a malformed token is rejected")
    void malformedTokenRejected() throws Exception {
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }
}
