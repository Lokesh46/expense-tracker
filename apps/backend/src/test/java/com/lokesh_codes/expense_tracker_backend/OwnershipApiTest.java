package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One account must never reach another's data.
 *
 * <p>Every cross-account attempt is expected to return 404 rather than 403:
 * "forbidden" would confirm that the id exists, which is enough to enumerate
 * another user's records.
 */
class OwnershipApiTest extends ApiTestBase {

    @Test
    @DisplayName("a new account cannot see another account's transactions")
    void transactionsAreNotShared() throws Exception {
        String alice = signUp("alice");
        recordExpense(alice, categoryId(alice, "Groceries"), "Weekly shop", "42.00", "2026-08-10");

        String bob = signUp("bob");

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("another account's transaction cannot be read, changed or deleted")
    void transactionsAreProtectedById() throws Exception {
        String alice = signUp("alice");
        int transactionId = recordExpense(alice, categoryId(alice, "Groceries"), "Private", "9.99",
                "2026-08-10");

        String bob = signUp("bob");
        int bobsCategory = categoryId(bob, "Groceries");

        mockMvc.perform(get("/api/transactions/" + transactionId)
                .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(jsonBody(
                put("/api/transactions/" + transactionId).header("Authorization", bearer(bob)), """
                        {"categoryId":%d,"description":"Hijacked","amount":1.00,"currency":"GBP",
                         "date":"2026-08-10","paymentMethod":"Cash"}
                        """.formatted(bobsCategory)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/transactions/" + transactionId)
                .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        // Alice's record survived all three attempts unchanged.
        mockMvc.perform(get("/api/transactions/" + transactionId)
                .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Private"));
    }

    /**
     * Categories used to be global: one shared list that any account could
     * rename or delete for everyone.
     */
    @Test
    @DisplayName("categories belong to one account only")
    void categoriesAreNotShared() throws Exception {
        String alice = signUp("alice");

        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(alice)), """
                {"name":"Alice Only","color":"#22c55e"}
                """)).andExpect(status().isCreated());

        String bob = signUp("bob");

        // Bob sees his own eight seeded categories and nothing of Alice's.
        mockMvc.perform(get("/api/categories").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[?(@.name == 'Alice Only')]").isEmpty());
    }

    @Test
    @DisplayName("another account's category cannot be edited or deleted")
    void categoriesAreProtectedById() throws Exception {
        String alice = signUp("alice");
        int aliceCategory = categoryId(alice, "Groceries");

        String bob = signUp("bob");

        mockMvc.perform(jsonBody(
                put("/api/categories/" + aliceCategory).header("Authorization", bearer(bob)), """
                        {"name":"Renamed","color":"#e58267"}
                        """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/categories/" + aliceCategory)
                .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a transaction cannot be filed under another account's category")
    void cannotBorrowAnotherAccountsCategory() throws Exception {
        String alice = signUp("alice");
        int aliceCategory = categoryId(alice, "Groceries");

        String bob = signUp("bob");

        mockMvc.perform(jsonBody(
                post("/api/transactions").header("Authorization", bearer(bob)), """
                        {"categoryId":%d,"description":"Wrong owner","amount":5.00,
                         "currency":"GBP","date":"2026-08-10","paymentMethod":"Cash"}
                        """.formatted(aliceCategory)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a budget cannot be set against another account's category")
    void budgetsRespectCategoryOwnership() throws Exception {
        String alice = signUp("alice");
        int aliceCategory = categoryId(alice, "Groceries");

        String bob = signUp("bob");

        mockMvc.perform(jsonBody(post("/api/budgets").header("Authorization", bearer(bob)), """
                {"categoryId":%d,"monthlyLimit":100.00}
                """.formatted(aliceCategory)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("recurring rules are scoped to their owner")
    void recurringRulesAreNotShared() throws Exception {
        String alice = signUp("alice");

        mockMvc.perform(jsonBody(post("/api/recurring").header("Authorization", bearer(alice)), """
                {"categoryId":%d,"description":"Rent","amount":1200.00,"currency":"GBP",
                 "paymentMethod":"Bank Transfer","frequency":"MONTHLY","nextRunDate":"2026-08-01"}
                """.formatted(categoryId(alice, "Rent & Bills"))))
                .andExpect(status().isCreated());

        String bob = signUp("bob");

        mockMvc.perform(get("/api/recurring").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
