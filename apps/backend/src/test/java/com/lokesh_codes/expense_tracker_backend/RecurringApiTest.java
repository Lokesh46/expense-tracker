package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Generation steps one period at a time, so a rule that lay dormant produces
 * every entry it missed rather than one lump. These are written relative to
 * today so they keep meaning as time passes.
 */
class RecurringApiTest extends ApiTestBase {

    private String token;
    private int rent;

    @BeforeEach
    void setUp() throws Exception {
        token = signUp("subscriber");
        rent = categoryId(token, "Rent & Bills");
    }

    private void createRule(String description, String frequency, LocalDate startOn) throws Exception {
        mockMvc.perform(jsonBody(post("/api/recurring").header("Authorization", bearer(token)), """
                {"categoryId":%d,"description":"%s","amount":100.00,"currency":"GBP",
                 "paymentMethod":"Bank Transfer","frequency":"%s","nextRunDate":"%s"}
                """.formatted(rent, description, frequency, startOn)))
                .andExpect(status().isCreated());
    }

    private int runDue() throws Exception {
        String body = mockMvc.perform(post("/api/recurring/run").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parse(body).get("created").asInt();
    }

    @Test
    @DisplayName("a rule dormant for months produces every entry it missed")
    void catchesUpOnEveryMissedPeriod() throws Exception {
        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);
        createRule("Rent", "MONTHLY", threeMonthsAgo);

        // Four: the start month plus the three that followed.
        org.assertj.core.api.Assertions.assertThat(runDue()).isEqualTo(4);

        mockMvc.perform(get("/api/transactions?search=Rent").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    @DisplayName("running twice does not duplicate anything")
    void generationIsNotRepeated() throws Exception {
        createRule("Rent", "MONTHLY", LocalDate.now().minusMonths(1));

        int first = runDue();
        int second = runDue();

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(2);
        // The rule has advanced past today, so a second pass owes nothing.
        org.assertj.core.api.Assertions.assertThat(second).isZero();
    }

    @Test
    @DisplayName("a rule dated in the future generates nothing yet")
    void futureRulesWait() throws Exception {
        createRule("Next year", "MONTHLY", LocalDate.now().plusMonths(2));

        org.assertj.core.api.Assertions.assertThat(runDue()).isZero();

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("generation stops at the end date and deactivates the rule")
    void stopsAtTheEndDate() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(5);
        LocalDate end = LocalDate.now().minusMonths(3);

        mockMvc.perform(jsonBody(post("/api/recurring").header("Authorization", bearer(token)), """
                {"categoryId":%d,"description":"Old subscription","amount":10.00,"currency":"GBP",
                 "paymentMethod":"Card","frequency":"MONTHLY","nextRunDate":"%s","endDate":"%s"}
                """.formatted(rent, start, end)))
                .andExpect(status().isCreated());

        // Months 5, 4 and 3 back, then it stops.
        org.assertj.core.api.Assertions.assertThat(runDue()).isEqualTo(3);

        mockMvc.perform(get("/api/recurring").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    @DisplayName("a paused rule generates nothing")
    void pausedRulesDoNotRun() throws Exception {
        createRule("Paused", "MONTHLY", LocalDate.now().minusMonths(2));

        String body = mockMvc.perform(get("/api/recurring").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        int id = parse(body).get(0).get("id").asInt();
        String nextRun = parse(body).get(0).get("nextRunDate").asText();

        mockMvc.perform(jsonBody(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/recurring/" + id).header("Authorization", bearer(token)), """
                        {"categoryId":%d,"description":"Paused","amount":100.00,"currency":"GBP",
                         "paymentMethod":"Bank Transfer","frequency":"MONTHLY",
                         "nextRunDate":"%s","active":false}
                        """.formatted(rent, nextRun)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(runDue()).isZero();
    }

    @Test
    @DisplayName("generated entries carry the rule's details")
    void generatedEntriesMatchTheRule() throws Exception {
        createRule("Rent", "MONTHLY", LocalDate.now().minusDays(1));

        runDue();

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Rent"))
                .andExpect(jsonPath("$.content[0].amount").value(100.00))
                .andExpect(jsonPath("$.content[0].currency").value("GBP"))
                .andExpect(jsonPath("$.content[0].paymentMethod").value("Bank Transfer"))
                .andExpect(jsonPath("$.content[0].categoryName").value("Rent & Bills"));
    }

    /**
     * Listing transactions runs anything due first, so entries appear even when
     * the nightly sweep has not fired — which is the normal case for a service
     * that sleeps between requests.
     */
    @Test
    @DisplayName("listing transactions materialises anything already due")
    void listingTriggersCatchUp() throws Exception {
        createRule("Rent", "MONTHLY", LocalDate.now().minusDays(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("a weekly rule advances by weeks")
    void weeklyRulesUseWeeks() throws Exception {
        createRule("Weekly", "WEEKLY", LocalDate.now().minusWeeks(3));

        // Start, plus three weeks that followed.
        org.assertj.core.api.Assertions.assertThat(runDue()).isEqualTo(4);
    }
}
