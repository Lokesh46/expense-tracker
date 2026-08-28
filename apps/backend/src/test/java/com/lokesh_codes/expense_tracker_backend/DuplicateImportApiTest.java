package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Importing a statement twice must not silently double a ledger.
 *
 * <p>The rows are still written — losing data on suspicion is worse than showing
 * a badge — so what these check is that they arrive marked, and that the owner
 * can act on the mark.
 */
class DuplicateImportApiTest extends ApiTestBase {

    private static final String STATEMENT = """
            Date,Description,Category,Amount,Currency,Payment Method,Comments
            2026-08-10,Corner shop,Groceries,12.50,GBP,Card,
            2026-08-11,Bus fare,Transport,2.40,GBP,Cash,
            """;

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions importFile(String token, String content) throws Exception {
        return mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(content))
                .header("Authorization", bearer(token)));
    }

    /** The id of the first row currently carrying the duplicate flag. */
    private int firstFlaggedId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/transactions?possibleDuplicate=true")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        return parse(body).get("content").get(0).get("id").asInt();
    }

    @Test
    @DisplayName("importing the same statement twice flags every row the second time")
    void reimportFlagsEverything() throws Exception {
        String token = signUp("twice");

        importFile(token, STATEMENT)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.flagged").value(0));

        importFile(token, STATEMENT)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.flagged").value(2));

        // Nothing was withheld: the rows are there, and marked.
        mockMvc.perform(get("/api/transactions?possibleDuplicate=true")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/transactions?possibleDuplicate=false")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a row repeated within one file is flagged too")
    void repeatsInsideOneFileAreFlagged() throws Exception {
        String token = signUp("repeated");

        importFile(token, """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,Corner shop,Groceries,12.50,GBP,Card,
                2026-08-10,Corner shop,Groceries,12.50,GBP,Card,
                """)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.flagged").value(1));
    }

    @Test
    @DisplayName("a genuinely different row is not flagged")
    void differentRowsAreLeftAlone() throws Exception {
        String token = signUp("distinct");

        importFile(token, STATEMENT).andExpect(jsonPath("$.flagged").value(0));

        // Same merchant and day, different amount: not the same payment.
        importFile(token, """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,Corner shop,Groceries,9.99,GBP,Card,
                """)
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.flagged").value(0));
    }

    @Test
    @DisplayName("punctuation and casing in a description do not make it a different payment")
    void descriptionIsNormalisedBeforeComparing() throws Exception {
        String token = signUp("normalised");

        importFile(token, """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,TESCO STORES  3421,Groceries,20.00,GBP,Card,
                """)
                .andExpect(jsonPath("$.flagged").value(0));

        // The same payment as the bank chose to spell it the following month.
        importFile(token, """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,Tesco Stores 3421,Groceries,20.00,GBP,Card,
                """)
                .andExpect(jsonPath("$.flagged").value(1));
    }

    @Test
    @DisplayName("a flagged row still counts toward totals until it is reviewed")
    void flaggedRowsStillCount() throws Exception {
        String token = signUp("counted");

        importFile(token, STATEMENT);
        importFile(token, STATEMENT);

        // The accepted cost of importing rather than withholding. The banner in
        // the client says so; this is where the behaviour is pinned down.
        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        assertThat(exported.lines().filter(line -> line.contains("Corner shop")).count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an owner can confirm a flagged row is genuine")
    void markingNotDuplicateClearsTheFlag() throws Exception {
        String token = signUp("confirmer");

        importFile(token, STATEMENT);
        importFile(token, STATEMENT);

        int flaggedId = firstFlaggedId(token);

        mockMvc.perform(put("/api/transactions/" + flaggedId + "/not-duplicate")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.possibleDuplicate").value(false));

        mockMvc.perform(get("/api/transactions?possibleDuplicate=true")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("one account cannot clear a flag on another account's row")
    void clearingIsOwnershipScoped() throws Exception {
        String alice = signUp("alice-dup");
        importFile(alice, STATEMENT);
        importFile(alice, STATEMENT);

        int flaggedId = firstFlaggedId(alice);

        String bob = signUp("bob-dup");
        mockMvc.perform(put("/api/transactions/" + flaggedId + "/not-duplicate")
                .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }
}
