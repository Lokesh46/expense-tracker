package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A budget stores only its limit; spend is derived from the transactions of the
 * month being asked about. That is the behaviour worth pinning down, because it
 * is what keeps past months truthful when a limit changes.
 */
class BudgetApiTest extends ApiTestBase {

    private String token;
    private int groceries;

    @BeforeEach
    void setUp() throws Exception {
        token = signUp("planner");
        groceries = categoryId(token, "Groceries");
    }

    private void setBudget(String limit) throws Exception {
        mockMvc.perform(jsonBody(post("/api/budgets").header("Authorization", bearer(token)), """
                {"categoryId":%d,"monthlyLimit":%s}
                """.formatted(groceries, limit)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("spend is counted only from the month being viewed")
    void spendIsScopedToTheRequestedMonth() throws Exception {
        setBudget("500.00");

        recordExpense(token, groceries, "August", "100.00", "2026-08-10");
        recordExpense(token, groceries, "Also August", "50.00", "2026-08-20");
        recordExpense(token, groceries, "September", "999.00", "2026-09-05");

        mockMvc.perform(get("/api/budgets?month=2026-08").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spent").value(150.00))
                .andExpect(jsonPath("$[0].remaining").value(350.00))
                .andExpect(jsonPath("$[0].percentUsed").value(30.0))
                .andExpect(jsonPath("$[0].exceeded").value(false));

        mockMvc.perform(get("/api/budgets?month=2026-09").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].spent").value(999.00))
                .andExpect(jsonPath("$[0].exceeded").value(true));
    }

    @Test
    @DisplayName("a month boundary is not off by a day")
    void monthBoundariesAreExact() throws Exception {
        setBudget("500.00");

        // The first and last day of August must count; the neighbours must not.
        recordExpense(token, groceries, "Last of July", "1.00", "2026-07-31");
        recordExpense(token, groceries, "First of August", "10.00", "2026-08-01");
        recordExpense(token, groceries, "Last of August", "20.00", "2026-08-31");
        recordExpense(token, groceries, "First of September", "1.00", "2026-09-01");

        mockMvc.perform(get("/api/budgets?month=2026-08").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].spent").value(30.00));
    }

    @Test
    @DisplayName("overspend is reported without a negative remainder")
    void overspendIsClamped() throws Exception {
        setBudget("100.00");
        recordExpense(token, groceries, "Splurge", "250.00", "2026-08-10");

        mockMvc.perform(get("/api/budgets?month=2026-08").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].spent").value(250.00))
                // "How much is left" is never negative; the overspend is carried
                // by percentUsed and exceeded instead.
                .andExpect(jsonPath("$[0].remaining").value(0.00))
                .andExpect(jsonPath("$[0].percentUsed").value(250.0))
                .andExpect(jsonPath("$[0].exceeded").value(true));
    }

    @Test
    @DisplayName("a month with no spending reports zero, not an error")
    void emptyMonthIsZero() throws Exception {
        setBudget("500.00");

        mockMvc.perform(get("/api/budgets?month=2026-01").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spent").value(0.00))
                .andExpect(jsonPath("$[0].remaining").value(500.00))
                .andExpect(jsonPath("$[0].exceeded").value(false));
    }

    @Test
    @DisplayName("a category can only have one budget")
    void oneBudgetPerCategory() throws Exception {
        setBudget("500.00");

        mockMvc.perform(jsonBody(post("/api/budgets").header("Authorization", bearer(token)), """
                {"categoryId":%d,"monthlyLimit":300.00}
                """.formatted(groceries)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("changing the limit does not rewrite what was already spent")
    void editingTheLimitLeavesSpendAlone() throws Exception {
        setBudget("500.00");
        recordExpense(token, groceries, "Shop", "120.00", "2026-08-10");

        String body = mockMvc.perform(get("/api/budgets?month=2026-08")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        int id = parse(body).get(0).get("id").asInt();

        mockMvc.perform(jsonBody(put("/api/budgets/" + id).header("Authorization", bearer(token)), """
                {"categoryId":%d,"monthlyLimit":100.00}
                """.formatted(groceries)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/budgets?month=2026-08").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].monthlyLimit").value(100.00))
                .andExpect(jsonPath("$[0].spent").value(120.00))
                .andExpect(jsonPath("$[0].exceeded").value(true));
    }

    @Test
    @DisplayName("deleting a budget leaves the transactions intact")
    void deletingABudgetKeepsTransactions() throws Exception {
        setBudget("500.00");
        recordExpense(token, groceries, "Shop", "20.00", "2026-08-10");

        String body = mockMvc.perform(get("/api/budgets").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        int id = parse(body).get(0).get("id").asInt();

        mockMvc.perform(delete("/api/budgets/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
