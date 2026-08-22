package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * CSV import has to cope with what banks actually export, and one unreadable
 * line must not discard the rest of a statement.
 */
class CsvApiTest extends ApiTestBase {

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("imports a well-formed file")
    void importsCleanFile() throws Exception {
        String token = signUp("importer");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,Coffee,Groceries,3.50,GBP,Cash,morning
                        2026-08-11,Lunch,Eating Out,12.00,GBP,Credit Card,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));
    }

    @Test
    @DisplayName("handles quoted fields, embedded commas and escaped quotes")
    void handlesQuoting() throws Exception {
        String token = signUp("quoter");

        // The final field ends in a CSV-escaped quote, which produces a run of
        // three quote characters. The last one is escaped so it does not close
        // the Java text block early.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,"Dinner, with friends",Eating Out,"1,234.56",GBP,Card,"said ""great night""\"
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        // A naive split(",") would have produced "Dinner" and an amount of 1.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Dinner, with friends"))
                .andExpect(jsonPath("$.content[0].amount").value(1234.56))
                .andExpect(jsonPath("$.content[0].comments").value("said \"great night\""));
    }

    @Test
    @DisplayName("accepts the date layouts statements commonly use")
    void acceptsSeveralDateFormats() throws Exception {
        String token = signUp("dates");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,ISO,Groceries,1.00
                        02/08/2026,Slashes,Groceries,2.00
                        03-08-2026,Dashes,Groceries,3.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(3));
    }

    /**
     * Statements mark debits in several ways. An expense is stored as a positive
     * amount regardless of how the source wrote it.
     */
    @Test
    @DisplayName("normalises negative and parenthesised amounts")
    void normalisesNegativeAmounts() throws Exception {
        String token = signUp("signs");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,Minus,Groceries,-45.00
                        2026-08-02,Brackets,Groceries,(99.99)
                        2026-08-03,Symbol,Groceries,12.30
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(3));

        String body = mockMvc.perform(get("/api/transactions?sort=amount,asc")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        assertThat(parse(body).get("content").get(0).get("amount").asDouble()).isEqualTo(12.30);
        assertThat(parse(body).get("content").get(1).get("amount").asDouble()).isEqualTo(45.00);
        assertThat(parse(body).get("content").get(2).get("amount").asDouble()).isEqualTo(99.99);
    }

    @Test
    @DisplayName("a bad row is reported but the rest of the file still imports")
    void badRowsDoNotAbortTheImport() throws Exception {
        String token = signUp("messy");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,Good one,Groceries,10.00
                        not-a-date,Bad date,Groceries,10.00
                        2026-08-03,Missing amount,Groceries,
                        2026-08-04,Another good one,Groceries,20.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.errors.length()").value(2))
                // The message names the line so the file can be corrected.
                .andExpect(jsonPath("$.errors[0]").value(Matchers.containsString("Line 3")));
    }

    @Test
    @DisplayName("an unknown category is created for the importing account")
    void createsMissingCategories() throws Exception {
        String token = signUp("newcat");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,Vet bill,Pet Care,55.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[?(@.name == 'Pet Care')]").isNotEmpty());
    }

    @Test
    @DisplayName("a file without a header row still imports")
    void headerIsOptional() throws Exception {
        String token = signUp("noheader");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("2026-08-01,No header here,Groceries,7.00\n"))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    @DisplayName("export offers a download and round-trips through import")
    void exportRoundTrips() throws Exception {
        String token = signUp("exporter");
        recordExpense(token, categoryId(token, "Groceries"), "Weekly shop", "42.75", "2026-08-10");

        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();

        assertThat(exported).startsWith("Date,Description,Category,Amount");
        assertThat(exported).contains("2026-08-10,Weekly shop,Groceries,42.75,GBP");

        // Feeding the export back in reproduces the same rows, which is the
        // property that makes an export worth having.
        String other = signUp("receiver");
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(exported))
                .header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.content[0].amount").value(42.75))
                .andExpect(jsonPath("$.content[0].description").value("Weekly shop"));
    }

    @Test
    @DisplayName("export honours the same filters as the search endpoint")
    void exportRespectsFilters() throws Exception {
        String token = signUp("filtered");
        int groceries = categoryId(token, "Groceries");
        int transport = categoryId(token, "Transport");

        recordExpense(token, groceries, "Included", "10.00", "2026-08-10");
        recordExpense(token, transport, "Excluded", "20.00", "2026-08-11");

        String exported = mockMvc.perform(get("/api/transactions/export?categoryId=" + groceries)
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        assertThat(exported).contains("Included");
        assertThat(exported).doesNotContain("Excluded");
    }

    @Test
    @DisplayName("an export only ever contains the requesting account's rows")
    void exportIsScopedToTheAccount() throws Exception {
        String alice = signUp("alice");
        recordExpense(alice, categoryId(alice, "Groceries"), "Alice private", "5.00", "2026-08-10");

        String bob = signUp("bob");
        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(bob)))
                .andReturn().getResponse().getContentAsString();

        assertThat(exported).doesNotContain("Alice private");
    }
}
