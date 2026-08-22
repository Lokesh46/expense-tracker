package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.lokesh_codes.expense_tracker_backend.entity.Role;

/**
 * Account administration.
 *
 * <p>Driven through MockMvc rather than by calling the service, because the
 * things most worth guarding here are the filter chain and the role rule, and
 * neither exists until the whole stack is assembled.
 */
class AdminUserApiTest extends ApiTestBase {

    @Nested
    @DisplayName("Only administrators can reach the admin API")
    class AccessControl {

        @Test
        void memberIsRefusedEveryAdminEndpoint() throws Exception {
            String member = signUp("mia");
            String otherId = String.valueOf(userIdOf("mia"));

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(member)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/users/stats").header("Authorization", bearer(member)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/users/" + otherId).header("Authorization", bearer(member)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/activity").header("Authorization", bearer(member)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/admin/users/" + otherId).header("Authorization", bearer(member)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void unauthenticatedRequestIsRefused() throws Exception {
            mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        }

        @Test
        void administratorIsAllowedIn() throws Exception {
            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        /**
         * A demotion has to bite immediately. If it waited for the token to expire,
         * the screen that performs it would be describing an intention rather than
         * a fact for up to the token's whole lifetime.
         */
        @Test
        void demotedAdministratorLosesAccessOnTheNextRequest() throws Exception {
            String first = signUpAdmin("root");
            signUp("second");

            int secondId = userIdOf("second");
            mockMvc.perform(jsonBody(patch("/api/admin/users/" + secondId)
                    .header("Authorization", bearer(first)), """
                    {"role":"ADMIN"}
                    """)).andExpect(status().isOk());

            String secondToken = tokenFor("second");
            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(secondToken)))
                    .andExpect(status().isOk());

            // Demoted by the other administrator, using a token issued before it.
            mockMvc.perform(jsonBody(patch("/api/admin/users/" + secondId)
                    .header("Authorization", bearer(first)), """
                    {"role":"MEMBER"}
                    """)).andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(secondToken)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Administration grants no sight of anybody's money")
    class Privacy {

        @Test
        void administratorSeesOnlyTheirOwnLedger() throws Exception {
            String member = signUp("mia");
            int transactionId = recordExpense(member, categoryId(member, "Groceries"),
                    "Weekly shop", "42.50", "2026-08-01");

            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/transactions").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));

            // 404 rather than 403: "forbidden" would confirm the id exists.
            mockMvc.perform(get("/api/transactions/" + transactionId)
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void userDetailReportsCountsWithoutContents() throws Exception {
            String member = signUp("mia");
            recordExpense(member, categoryId(member, "Groceries"), "Weekly shop", "42.50", "2026-08-01");
            recordExpense(member, categoryId(member, "Transport"), "Season ticket", "180.00", "2026-08-02");

            String admin = signUpAdmin("root");

            String body = mockMvc.perform(get("/api/admin/users/" + userIdOf("mia"))
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account.username").value("mia"))
                    .andExpect(jsonPath("$.transactionCount").value(2))
                    .andExpect(jsonPath("$.categoryCount").value(8))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // The counts are the whole point: two transactions, and no way to know
            // what either of them was.
            assertThat(body).doesNotContain("Weekly shop", "Season ticket", "42.50", "180.00");
        }
    }

    @Nested
    @DisplayName("Listing, searching and filtering")
    class Listing {

        @Test
        void searchMatchesUsernameAndEmail() throws Exception {
            signUp("mia");
            signUp("noah");
            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/admin/users").param("search", "mia")
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("mia"));

            // signUp gives every account "<username>@example.com".
            mockMvc.perform(get("/api/admin/users").param("search", "noah@example")
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("noah"));
        }

        @Test
        void filtersByRoleAndStatus() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/admin/users").param("role", "ADMIN")
                    .header("Authorization", bearer(admin)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("root"));

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + userIdOf("mia"))
                    .header("Authorization", bearer(admin)), """
                    {"active":false}
                    """)).andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/users").param("status", "SUSPENDED")
                    .header("Authorization", bearer(admin)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("mia"));

            mockMvc.perform(get("/api/admin/users").param("status", "ACTIVE")
                    .header("Authorization", bearer(admin)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("root"));
        }

        /**
         * {@code ?sort=password} orders the list by password hash, which is a small
         * but real oracle, and an unknown property throws from inside Hibernate
         * rather than returning a 400. Both are handled by ignoring the name.
         */
        @Test
        void sortIsLimitedToWhitelistedColumns() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/admin/users").param("sort", "password,asc")
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));

            mockMvc.perform(get("/api/admin/users").param("sort", "nonsense")
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }

        @Test
        void statsCountRolesAndStatuses() throws Exception {
            signUp("mia");
            signUp("noah");
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + userIdOf("noah"))
                    .header("Authorization", bearer(admin)), """
                    {"active":false}
                    """)).andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/users/stats").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalUsers").value(3))
                    .andExpect(jsonPath("$.admins").value(1))
                    .andExpect(jsonPath("$.members").value(2))
                    .andExpect(jsonPath("$.suspended").value(1))
                    .andExpect(jsonPath("$.active").value(2))
                    .andExpect(jsonPath("$.joinedLast7Days").value(3));
        }
    }

    @Nested
    @DisplayName("Creating and changing accounts")
    class Managing {

        @Test
        void administratorCreatesAnAccountThatCanSignIn() throws Exception {
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(post("/api/admin/users")
                    .header("Authorization", bearer(admin)), """
                    {"username":"mia","password":"%s","email":"mia@example.com","role":"MEMBER"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.username").value("mia"))
                    .andExpect(jsonPath("$.role").value("MEMBER"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // Seeded like a self-registration, so they do not arrive to empty screens.
            String token = tokenFor("mia");
            mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(8));
        }

        @Test
        void createdAccountCanBeAnAdministrator() throws Exception {
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(post("/api/admin/users")
                    .header("Authorization", bearer(admin)), """
                    {"username":"second","password":"%s","role":"ADMIN"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(tokenFor("second"))))
                    .andExpect(status().isOk());
        }

        @Test
        void duplicateUsernameIsRefused() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(post("/api/admin/users")
                    .header("Authorization", bearer(admin)), """
                    {"username":"mia","password":"%s","role":"MEMBER"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isConflict());
        }

        /** A role outside the enum is a client error, not a server one. */
        @Test
        void unknownRoleIsRejectedAsBadRequest() throws Exception {
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(post("/api/admin/users")
                    .header("Authorization", bearer(admin)), """
                    {"username":"mia","password":"%s","role":"SUPERUSER"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void emailAndRoleAreUpdatedIndependently() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");
            int id = userIdOf("mia");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + id)
                    .header("Authorization", bearer(admin)), """
                    {"email":"changed@example.com"}
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("changed@example.com"))
                    // Untouched, because it was not sent.
                    .andExpect(jsonPath("$.role").value("MEMBER"));

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + id)
                    .header("Authorization", bearer(admin)), """
                    {"role":"ADMIN"}
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("changed@example.com"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        void administratorResetsAPasswordAndTheOldOneStopsWorking() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(post("/api/admin/users/" + userIdOf("mia") + "/password")
                    .header("Authorization", bearer(admin)), """
                    {"newPassword":"BrandNewPass1!"}
                    """)).andExpect(status().isNoContent());

            mockMvc.perform(jsonBody(post("/authenticate"), """
                    {"username":"mia","password":"%s"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(jsonBody(post("/authenticate"), """
                    {"username":"mia","password":"BrandNewPass1!"}
                    """)).andExpect(status().isOk());
        }

        @Test
        void suspensionEndsAnExistingSessionImmediately() throws Exception {
            String member = signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                    .andExpect(status().isOk());

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + userIdOf("mia"))
                    .header("Authorization", bearer(admin)), """
                    {"active":false}
                    """)).andExpect(status().isOk());

            mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(jsonBody(post("/authenticate"), """
                    {"username":"mia","password":"%s"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void reinstatingRestoresSignIn() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");
            int id = userIdOf("mia");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + id)
                    .header("Authorization", bearer(admin)), "{\"active\":false}"))
                    .andExpect(status().isOk());
            mockMvc.perform(jsonBody(patch("/api/admin/users/" + id)
                    .header("Authorization", bearer(admin)), "{\"active\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            mockMvc.perform(jsonBody(post("/authenticate"), """
                    {"username":"mia","password":"%s"}
                    """.formatted(PASSWORD)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Guards against locking the instance out")
    class Guards {

        @Test
        void administratorCannotChangeTheirOwnRole() throws Exception {
            String admin = signUpAdmin("root");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + userIdOf("root"))
                    .header("Authorization", bearer(admin)), """
                    {"role":"MEMBER"}
                    """))
                    .andExpect(status().isConflict());

            assertThat(userNamed("root").getRole()).isEqualTo(Role.ADMIN);
        }

        @Test
        void administratorCannotSuspendOrDeleteThemselves() throws Exception {
            String admin = signUpAdmin("root");
            int id = userIdOf("root");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + id)
                    .header("Authorization", bearer(admin)), "{\"active\":false}"))
                    .andExpect(status().isConflict());

            mockMvc.perform(delete("/api/admin/users/" + id).header("Authorization", bearer(admin)))
                    .andExpect(status().isConflict());
        }

        /**
         * With two administrators the guard must let go, or the rule protecting the
         * instance would also prevent ever reducing it back to one.
         */
        @Test
        void demotionIsAllowedWhenAnotherAdministratorRemains() throws Exception {
            String first = signUpAdmin("root");
            signUp("second");
            int secondId = userIdOf("second");

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + secondId)
                    .header("Authorization", bearer(first)), "{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(jsonBody(patch("/api/admin/users/" + secondId)
                    .header("Authorization", bearer(first)), "{\"role\":\"MEMBER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("MEMBER"));
        }
    }

    @Nested
    @DisplayName("Deleting an account")
    class Deleting {

        @Test
        void deleteRemovesEverythingTheAccountOwned() throws Exception {
            String member = signUp("mia");
            recordExpense(member, categoryId(member, "Groceries"), "Weekly shop", "42.50", "2026-08-01");

            String admin = signUpAdmin("root");
            int id = userIdOf("mia");

            mockMvc.perform(delete("/api/admin/users/" + id).header("Authorization", bearer(admin)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/admin/users/" + id).header("Authorization", bearer(admin)))
                    .andExpect(status().isNotFound());

            // The children go with it; a leftover row would fail its foreign key.
            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                    .andExpect(jsonPath("$.totalElements").value(1));

            // And the old token is dead, not merely unhelpful.
            mockMvc.perform(get("/api/categories").header("Authorization", bearer(member)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * The audit trail is not a child of the account. Deleting a user is exactly
         * when its history matters most.
         */
        @Test
        void deleteKeepsTheAuditTrail() throws Exception {
            signUp("mia");
            String admin = signUpAdmin("root");

            mockMvc.perform(delete("/api/admin/users/" + userIdOf("mia"))
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/admin/activity").param("username", "mia")
                    .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].action").value("ACCOUNT_DELETED"))
                    .andExpect(jsonPath("$.content[0].actor").value("root"));
        }
    }
}
