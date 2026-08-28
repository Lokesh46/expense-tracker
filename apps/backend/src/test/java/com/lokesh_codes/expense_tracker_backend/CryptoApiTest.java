package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lokesh_codes.expense_tracker_backend.service.crypto.EncryptionBackfill;

/**
 * Transaction text is encrypted in the database and still usable through the API.
 *
 * <p>These read the columns directly rather than going through JPA, because the
 * whole question is what is actually written down — and every path that goes
 * through the entity decrypts on the way past, which would hide the answer.
 */
class CryptoApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EncryptionBackfill backfill;

    private void record(String token, int categoryId, String description, String comments)
            throws Exception {
        mockMvc.perform(jsonBody(post("/api/transactions").header("Authorization", bearer(token)),
                """
                        {"categoryId":%d,"description":"%s","amount":42.00,"currency":"GBP",
                         "date":"2026-08-10","paymentMethod":"Card","comments":"%s"}
                        """.formatted(categoryId, description, comments)))
                .andExpect(status().isCreated());
    }

    private List<String> rawDescriptions() {
        return jdbc.queryForList("select description from transactions", String.class);
    }

    @Test
    @DisplayName("a description is unreadable in the database and intact through the API")
    void descriptionsAreEncryptedAtRest() throws Exception {
        String token = signUp("encrypted");
        record(token, categoryId(token, "Health"), "Therapy session", "monthly");

        // What is actually on disk.
        List<String> stored = rawDescriptions();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).startsWith("v1:");
        assertThat(stored.get(0)).doesNotContain("Therapy");

        List<String> storedComments = jdbc.queryForList("select comments from transactions",
                String.class);
        assertThat(storedComments.get(0)).startsWith("v1:");
        assertThat(storedComments.get(0)).doesNotContain("monthly");

        // What the owner sees.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Therapy session"))
                .andExpect(jsonPath("$.content[0].comments").value("monthly"));
    }

    @Test
    @DisplayName("the amount stays readable, because budgets are summed in the database")
    void amountsAreNotEncrypted() throws Exception {
        String token = signUp("summable");
        record(token, categoryId(token, "Health"), "Consultation", "");

        // Encrypting this column would move every budget and dashboard total into
        // Java over a full result set. The tradeoff is deliberate and this is
        // where it is written down.
        Double summed = jdbc.queryForObject("select sum(amount) from transactions", Double.class);
        assertThat(summed).isEqualTo(42.00);
    }

    @Test
    @DisplayName("an encrypted description is still searchable by whole word")
    void searchFindsEncryptedText() throws Exception {
        String token = signUp("searcher");
        int health = categoryId(token, "Health");
        record(token, health, "Starbucks Coffee", "with Dan");
        record(token, health, "Tesco Metro", "");

        mockMvc.perform(get("/api/transactions?search=starbucks")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Starbucks Coffee"));

        // The index covers comments as well as the description.
        mockMvc.perform(get("/api/transactions?search=dan")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Every word in the query has to be present, not just one of them.
        mockMvc.perform(get("/api/transactions?search=starbucks tesco")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("searching part of a word finds nothing, which is the cost of the index")
    void substringSearchNoLongerMatches() throws Exception {
        String token = signUp("fragment");
        record(token, categoryId(token, "Health"), "Starbucks Coffee", "");

        // Not a defect to be fixed later without changing the index: a keyed
        // digest of a word says nothing about its prefixes, and that is exactly
        // the property that makes the column safe to store next to the
        // ciphertext. Asserted so the behaviour is a decision, not a surprise.
        mockMvc.perform(get("/api/transactions?search=star")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("the search index reveals neither the words nor their length")
    void indexDoesNotLeakContent() throws Exception {
        String token = signUp("opaque");
        record(token, categoryId(token, "Health"), "Therapy session", "");

        String tokens = jdbc.queryForObject("select search_tokens from transactions", String.class);
        assertThat(tokens).doesNotContain("therapy").doesNotContain("session");
        // Fixed-width digests, so a long word and a short one look the same.
        for (String digest : tokens.trim().split(" ")) {
            assertThat(digest).hasSize(12);
        }
    }

    @Test
    @DisplayName("a row written before encryption is still readable, and the backfill converts it")
    void plaintextRowsSurviveAndAreMigrated() throws Exception {
        String token = signUp("legacy");
        int category = categoryId(token, "Health");
        int userId = userIdOf("legacy");

        // Exactly what an existing database holds: plaintext, and no search index.
        jdbc.update("""
                insert into transactions
                    (user_id, category_id, description, amount, currency, date, payment_method, comments)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, category, "Old plaintext row", 15.00, "GBP",
                java.sql.Date.valueOf("2026-08-01"), "Cash", "written before encryption");

        // Readable straight away — the application must not need the backfill to
        // have run before it works.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Old plaintext row"));

        // But not yet findable, because it has no index.
        mockMvc.perform(get("/api/transactions?search=plaintext")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(backfill.backfill()).isPositive();

        List<String> stored = rawDescriptions();
        assertThat(stored.get(0)).startsWith("v1:");

        mockMvc.perform(get("/api/transactions?search=plaintext")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Old plaintext row"));
    }

    @Test
    @DisplayName("running the backfill twice changes nothing the second time")
    void backfillIsIdempotent() throws Exception {
        String token = signUp("twice");
        record(token, categoryId(token, "Health"), "Already encrypted", "");

        backfill.backfill();
        String afterFirst = rawDescriptions().get(0);

        backfill.backfill();

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Already encrypted"));
        // A row that was already done is not touched again.
        assertThat(rawDescriptions().get(0)).isEqualTo(afterFirst);
    }
}
