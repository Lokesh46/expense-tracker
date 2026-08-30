package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Filing an imported statement by learning from how the account has filed
 * before, and reviewing what was guessed.
 *
 * <p>The behaviour worth protecting is not that a guess is made — it is which
 * guesses are trusted. An import that learns from its own guesses would take one
 * mistake and harden it into a permanent fact, growing more confident every
 * month, and nothing on screen would say so.
 */
class CategorySuggestionApiTest extends ApiTestBase {

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** A statement of Swiggy orders, with no category column at all. */
    private MockMultipartFile swiggyStatement(String... dates) {
        StringBuilder csv = new StringBuilder("Date,Description,Amount,Currency\n");
        for (String date : dates) {
            csv.append(date).append(",UPI-SWIGGY-").append(date.hashCode() & 0xffff)
                    .append(",12.00,GBP\n");
        }
        return csv(csv.toString());
    }

    private ResultActions importStatement(String token, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/transactions/import")
                .file(file)
                .header("Authorization", bearer(token)));
    }

    /** Every transaction on the account, newest first. */
    private JsonNode transactions(String token) throws Exception {
        return parse(mockMvc.perform(get("/api/transactions?size=50")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("content");
    }

    private JsonNode reviewQueue(String token) throws Exception {
        return parse(mockMvc.perform(get("/api/review/merchants")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Files the same merchant by hand the given number of times. */
    private void fileByHand(String token, String category, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            recordExpense(token, categoryId(token, category), "UPI-SWIGGY-" + i, "12.00",
                    "2026-07-0" + (i + 1));
        }
    }

    // ------------------------------------------------------------ suggesting

    @Test
    @DisplayName("with nothing to go on, rows are filed as uncategorised and held for review")
    void noHistoryMeansNoGuess() throws Exception {
        String token = signUp("suggestcold");

        importStatement(token, swiggyStatement("2026-08-01", "2026-08-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.needsReview").value(2));

        JsonNode rows = transactions(token);
        assertThat(rows).hasSize(2);
        rows.forEach(row -> {
            assertThat(row.get("categoryName").asText()).isEqualTo("Uncategorised");
            assertThat(row.get("categoryConfirmed").asBoolean()).isFalse();
            assertThat(row.get("categorySource").asText()).isEqualTo("NONE");
        });
    }

    @Test
    @DisplayName("a merchant filed the same way three times is filed that way without asking")
    void settledHistoryIsAppliedAndTrusted() throws Exception {
        String token = signUp("suggestlearn");
        fileByHand(token, "Eating Out", 3);

        importStatement(token, swiggyStatement("2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                // Nothing to review: three consistent decisions is not a guess.
                .andExpect(jsonPath("$.needsReview").value(0));

        JsonNode imported = transactions(token).get(0);
        assertThat(imported.get("categoryName").asText()).isEqualTo("Eating Out");
        assertThat(imported.get("categoryConfirmed").asBoolean()).isTrue();
        assertThat(imported.get("categorySource").asText()).isEqualTo("HISTORY");
    }

    @Test
    @DisplayName("a thinner history is still applied, but left for review")
    void thinHistoryIsAppliedButNotTrusted() throws Exception {
        String token = signUp("suggestthin");
        fileByHand(token, "Eating Out", 2);

        importStatement(token, swiggyStatement("2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsReview").value(1));

        JsonNode imported = transactions(token).get(0);
        // Applied, so the dashboard is roughly right straight away...
        assertThat(imported.get("categoryName").asText()).isEqualTo("Eating Out");
        // ...but not counted as agreed, so it neither settles nor teaches.
        assertThat(imported.get("categoryConfirmed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a rule the user wrote beats what history suggests")
    void ruleOutranksHistory() throws Exception {
        String token = signUp("suggestrule");
        fileByHand(token, "Eating Out", 3);

        mockMvc.perform(jsonBody(post("/api/category-rules")
                .header("Authorization", bearer(token)), """
                {"pattern":"swiggy","matchType":"CONTAINS","categoryId":%d,"active":true}
                """.formatted(categoryId(token, "Groceries"))))
                .andExpect(status().isCreated());

        importStatement(token, swiggyStatement("2026-08-01")).andExpect(status().isOk());

        JsonNode imported = transactions(token).get(0);
        assertThat(imported.get("categoryName").asText()).isEqualTo("Groceries");
        assertThat(imported.get("categorySource").asText()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("an import never learns from its own guesses")
    void guessesAreNotEvidence() throws Exception {
        // The one that matters. Five rows filed as Uncategorised by an import
        // look exactly like five consistent decisions if nothing distinguishes a
        // guess from a choice -- and the next import would then "learn" to file
        // this merchant under Uncategorised, for good.
        String token = signUp("suggestloop");

        importStatement(token, swiggyStatement("2026-08-01", "2026-08-02", "2026-08-03",
                "2026-08-04", "2026-08-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsReview").value(5));

        importStatement(token, swiggyStatement("2026-08-06"))
                .andExpect(status().isOk())
                // Still a guess, still unreviewed. Five unconfirmed rows taught
                // it nothing.
                .andExpect(jsonPath("$.needsReview").value(1));

        JsonNode rows = transactions(token);
        rows.forEach(row -> assertThat(row.get("categoryConfirmed").asBoolean()).isFalse());
    }

    // --------------------------------------------------------------- review

    @Test
    @DisplayName("the queue groups a statement by merchant rather than by row")
    void queueIsGroupedByMerchant() throws Exception {
        String token = signUp("reviewgroup");

        importStatement(token, csv("""
                Date,Description,Amount,Currency
                2026-08-01,UPI-SWIGGY-1234,12.00,GBP
                2026-08-02,UPI-SWIGGY-9988,18.00,GBP
                2026-08-03,POS 4123XXXX9876 SWIGGY BANGALORE,20.00,GBP
                2026-08-04,UPI-UBER-4321,7.50,GBP
                """)).andExpect(status().isOk());

        JsonNode queue = reviewQueue(token);
        // Four rows, two decisions -- including one Swiggy the bank wrote
        // completely differently.
        assertThat(queue.get("merchantsTotal").asInt()).isEqualTo(2);
        assertThat(queue.get("transactions").asInt()).isEqualTo(4);

        JsonNode biggest = queue.get("merchants").get(0);
        assertThat(biggest.get("merchantName").asText()).isEqualTo("swiggy");
        assertThat(biggest.get("transactionCount").asInt()).isEqualTo(3);
        assertThat(biggest.get("totals").get(0).get("currency").asText()).isEqualTo("GBP");
        assertThat(biggest.get("totals").get(0).get("amount").asDouble()).isEqualTo(50.00);
        // The samples exist so you can see what the merchant key swallowed
        // before approving three rows at once.
        assertThat(biggest.get("samples")).isNotEmpty();
    }

    @Test
    @DisplayName("approving a merchant settles its rows without moving them")
    void approveConfirmsWithoutRefiling() throws Exception {
        String token = signUp("reviewapprove");
        fileByHand(token, "Eating Out", 2);

        importStatement(token, swiggyStatement("2026-08-01", "2026-08-02"))
                .andExpect(status().isOk());

        String hash = reviewQueue(token).get("merchants").get(0).get("merchantHash").asText();

        mockMvc.perform(post("/api/review/merchants/" + hash + "/approve")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.ruleCreated").value(false));

        transactions(token).forEach(row -> {
            assertThat(row.get("categoryName").asText()).isEqualTo("Eating Out");
            assertThat(row.get("categoryConfirmed").asBoolean()).isTrue();
        });
        assertThat(reviewQueue(token).get("merchantsTotal").asInt()).isZero();
    }

    @Test
    @DisplayName("refiling a merchant moves what was guessed and leaves what was decided")
    void assignSparesConfirmedRows() throws Exception {
        String token = signUp("reviewassign");
        // One deliberate decision, which must survive.
        recordExpense(token, categoryId(token, "Eating Out"), "UPI-SWIGGY-0001", "12.00",
                "2026-07-01");

        importStatement(token, swiggyStatement("2026-08-01")).andExpect(status().isOk());

        String hash = reviewQueue(token).get("merchants").get(0).get("merchantHash").asText();
        int groceries = categoryId(token, "Groceries");

        mockMvc.perform(jsonBody(post("/api/review/merchants/" + hash + "/assign")
                .header("Authorization", bearer(token)),
                """
                        {"categoryId":%d,"createRule":true}
                        """.formatted(groceries)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.ruleCreated").value(true));

        JsonNode rows = transactions(token);
        assertThat(rows).hasSize(2);
        // Newest first: the imported row moved, the hand-recorded one did not.
        assertThat(rows.get(0).get("categoryName").asText()).isEqualTo("Groceries");
        assertThat(rows.get(1).get("categoryName").asText()).isEqualTo("Eating Out");
    }

    @Test
    @DisplayName("refiling remembers itself, so the merchant never returns to the queue")
    void assignWritesAFilingRule() throws Exception {
        String token = signUp("reviewremember");
        importStatement(token, swiggyStatement("2026-08-01")).andExpect(status().isOk());

        String hash = reviewQueue(token).get("merchants").get(0).get("merchantHash").asText();
        mockMvc.perform(jsonBody(post("/api/review/merchants/" + hash + "/assign")
                .header("Authorization", bearer(token)), """
                {"categoryId":%d,"createRule":true}
                """.formatted(categoryId(token, "Groceries"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/category-rules").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pattern").value("swiggy"))
                .andExpect(jsonPath("$[0].categoryName").value("Groceries"));

        // The point of writing it: the next statement files itself.
        importStatement(token, swiggyStatement("2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsReview").value(0));
        assertThat(reviewQueue(token).get("merchantsTotal").asInt()).isZero();
    }

    @Test
    @DisplayName("approving everything clears the queue in one go")
    void approveAll() throws Exception {
        String token = signUp("reviewall");
        importStatement(token, csv("""
                Date,Description,Amount,Currency
                2026-08-01,UPI-SWIGGY-1234,12.00,GBP
                2026-08-02,UPI-UBER-4321,7.50,GBP
                """)).andExpect(status().isOk());

        mockMvc.perform(post("/api/review/approve-all").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(reviewQueue(token).get("merchantsTotal").asInt()).isZero();
    }

    @Test
    @DisplayName("one account cannot approve or refile another's merchants")
    void merchantsAreScopedToTheirOwner() throws Exception {
        String owner = signUp("reviewowner");
        String stranger = signUp("reviewstranger");

        importStatement(owner, swiggyStatement("2026-08-01")).andExpect(status().isOk());
        String hash = reviewQueue(owner).get("merchants").get(0).get("merchantHash").asText();

        // Reported as missing rather than forbidden, so a digest cannot be
        // probed for whether it exists on somebody else's ledger.
        mockMvc.perform(post("/api/review/merchants/" + hash + "/approve")
                .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());

        assertThat(transactions(owner).get(0).get("categoryConfirmed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a merchant with nothing waiting is not reported as settled")
    void nothingToApproveIsNotFound() throws Exception {
        String token = signUp("reviewempty");

        mockMvc.perform(post("/api/review/merchants/whatever/approve")
                .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
}
