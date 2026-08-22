package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryApiTest extends ApiTestBase {

    @Test
    @DisplayName("a new account is seeded with starter categories")
    void registrationSeedsCategories() throws Exception {
        String token = signUp("newcomer");

        // Without these, a new account lands on empty screens and cannot record
        // anything until it invents a category first.
        mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[?(@.name == 'Groceries')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.name == 'Rent & Bills')]").isNotEmpty())
                // Each carries a colour so charts are legible immediately.
                .andExpect(jsonPath("$[0].color").isNotEmpty());
    }

    @Test
    @DisplayName("categories come back in alphabetical order")
    void categoriesAreSorted() throws Exception {
        String token = signUp("sorted");

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].name").value("Eating Out"))
                .andExpect(jsonPath("$[7].name").value("Transport"));
    }

    @Test
    @DisplayName("a duplicate name is refused, whatever the casing")
    void duplicateNamesRefused() throws Exception {
        String token = signUp("dupes");

        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(token)), """
                {"name":"groceries","color":"#22c55e"}
                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("two accounts may each have a category of the same name")
    void sameNameAllowedAcrossAccounts() throws Exception {
        String alice = signUp("alice");
        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(alice)), """
                {"name":"Hobbies","color":"#22c55e"}
                """)).andExpect(status().isCreated());

        String bob = signUp("bob");
        // The uniqueness constraint is per user, not global.
        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(bob)), """
                {"name":"Hobbies","color":"#e58267"}
                """)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a category in use cannot be deleted")
    void cannotDeleteACategoryInUse() throws Exception {
        String token = signUp("careful");
        int groceries = categoryId(token, "Groceries");
        recordExpense(token, groceries, "Weekly shop", "20.00", "2026-08-10");

        // Deleting would either orphan the transaction or fail on a constraint
        // deep in the persistence layer; refusing up front is actionable.
        mockMvc.perform(delete("/api/categories/" + groceries).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("1 transaction")));
    }

    @Test
    @DisplayName("an unused category can be deleted")
    void unusedCategoryCanBeDeleted() throws Exception {
        String token = signUp("tidy");
        int unused = categoryId(token, "Entertainment");

        mockMvc.perform(delete("/api/categories/" + unused).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("a category can be renamed to its own current name")
    void renamingToTheSameNameIsAllowed() throws Exception {
        String token = signUp("renamer");
        int groceries = categoryId(token, "Groceries");

        // The duplicate check must exclude the record being edited, or changing
        // only the colour would collide with itself.
        mockMvc.perform(jsonBody(
                put("/api/categories/" + groceries).header("Authorization", bearer(token)), """
                        {"name":"Groceries","color":"#ff0000"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#ff0000"));
    }

    @Test
    @DisplayName("renaming onto another existing category is refused")
    void renamingOntoAnotherNameRefused() throws Exception {
        String token = signUp("collider");
        int groceries = categoryId(token, "Groceries");

        mockMvc.perform(jsonBody(
                put("/api/categories/" + groceries).header("Authorization", bearer(token)), """
                        {"name":"Transport","color":"#22c55e"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a name that is too short is refused with a field message")
    void validatesName() throws Exception {
        String token = signUp("validator");

        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(token)), """
                {"name":"x"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
    }
}
