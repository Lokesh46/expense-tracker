package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Filtering, sorting and paging happen in the database. These guard the query
 * translation, which is where a wrong predicate silently returns the wrong rows
 * rather than failing.
 */
class TransactionSearchApiTest extends ApiTestBase {

    private String token;
    private int groceries;
    private int transport;

    @BeforeEach
    void seedLedger() throws Exception {
        token = signUp("searcher");
        groceries = categoryId(token, "Groceries");
        transport = categoryId(token, "Transport");

        recordExpense(token, groceries, "Weekly shop", "50.00", "2026-08-01");
        recordExpense(token, groceries, "Corner shop", "5.50", "2026-08-15");
        recordExpense(token, transport, "Train ticket", "120.00", "2026-08-20");
        recordExpense(token, transport, "Bus fare", "2.40", "2026-09-01");
    }

    @Test
    @DisplayName("results are paged, and the envelope reports the full count")
    void pagingReportsTheWholeResultSet() throws Exception {
        mockMvc.perform(get("/api/transactions?page=0&size=2")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/transactions?page=1&size=2")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("newest first is the default order")
    void defaultsToNewestFirst() throws Exception {
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Bus fare"))
                .andExpect(jsonPath("$.content[3].description").value("Weekly shop"));
    }

    @Test
    @DisplayName("sorting by amount is applied by the database, not the page")
    void sortsByAmountAcrossTheWholeSet() throws Exception {
        // Asking for one row proves the ordering is global: the largest amount
        // is not on the first page in date order.
        mockMvc.perform(get("/api/transactions?sort=amount,desc&size=1")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Train ticket"));

        mockMvc.perform(get("/api/transactions?sort=amount,asc&size=1")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Bus fare"));
    }

    @Test
    @DisplayName("filters by category")
    void filtersByCategory() throws Exception {
        mockMvc.perform(get("/api/transactions?categoryId=" + transport)
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("the date range includes both endpoints")
    void dateRangeIsInclusive() throws Exception {
        mockMvc.perform(get("/api/transactions?from=2026-08-01&to=2026-08-20")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(3));

        // A range that lands exactly on one entry must return that entry.
        mockMvc.perform(get("/api/transactions?from=2026-08-15&to=2026-08-15")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Corner shop"));
    }

    @Test
    @DisplayName("the amount range includes both endpoints")
    void amountRangeIsInclusive() throws Exception {
        mockMvc.perform(get("/api/transactions?minAmount=5.50&maxAmount=50.00")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("search matches description and notes, ignoring case")
    void searchIsCaseInsensitiveAcrossDescriptionAndNotes() throws Exception {
        mockMvc.perform(get("/api/transactions?search=SHOP")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(jsonBody(post("/api/transactions").header("Authorization", bearer(token)), """
                {"categoryId":%d,"description":"Nothing obvious","amount":3.00,"currency":"GBP",
                 "date":"2026-08-05","paymentMethod":"Cash","comments":"birthday present"}
                """.formatted(groceries)))
                .andExpect(status().isCreated());

        // Matched only via the notes field.
        mockMvc.perform(get("/api/transactions?search=birthday")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Nothing obvious"));
    }

    @Test
    @DisplayName("filters combine rather than replace each other")
    void filtersCombine() throws Exception {
        mockMvc.perform(get("/api/transactions?categoryId=" + groceries
                + "&from=2026-08-10&to=2026-08-31&search=shop")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Corner shop"));
    }

    @Test
    @DisplayName("an invalid amount is refused with a field message")
    void validatesAmount() throws Exception {
        mockMvc.perform(jsonBody(post("/api/transactions").header("Authorization", bearer(token)), """
                {"categoryId":%d,"description":"Free","amount":0,"currency":"GBP",
                 "date":"2026-08-05","paymentMethod":"Cash"}
                """.formatted(groceries)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").exists());
    }

    /**
     * Amounts are BigDecimal end to end; as doubles, 0.1 + 0.2 style drift shows
     * up once a column is summed.
     */
    @Test
    @DisplayName("an amount survives the round trip exactly")
    void amountIsExact() throws Exception {
        int id = recordExpense(token, groceries, "Precise", "0.07", "2026-08-02");

        mockMvc.perform(get("/api/transactions/" + id).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.amount").value(0.07));
    }
}
