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

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;

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
    @DisplayName("an unknown category name is reported, not silently created")
    void unknownCategoryNamesAreReported() throws Exception {
        String token = signUp("newcat");

        // This used to create whatever the column said, which turned one typo in
        // a bank export into a permanent category with no way to tell it from a
        // real one.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,Vet bill,Pet Care,55.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors[0]", Matchers.containsString("Pet Care")));

        mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[?(@.name == 'Pet Care')]").isEmpty());

        // The row is still imported — nothing is lost — it is just filed
        // somewhere the user can find and fix.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Vet bill"))
                .andExpect(jsonPath("$.content[0].categoryName").value("Uncategorised"));
    }

    @Test
    @DisplayName("a category name the account already has is still honoured")
    void knownCategoryNamesStillWork() throws Exception {
        String token = signUp("knowncat");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,Weekly shop,groceries,55.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors").isEmpty());

        // Matched case-insensitively against the account's own categories.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Groceries"));
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
    @DisplayName("an exported description cannot execute as a spreadsheet formula")
    void exportNeutralisesFormulas() throws Exception {
        String token = signUp("planter");

        // A description a user can set directly, or that arrives from an earlier
        // import. Opened in Excel without a guard, the cell is evaluated.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,=1+1,Groceries,5.00,GBP,Cash,@SUM(A1:A9)
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        assertThat(exported).contains("'=1+1");
        assertThat(exported).contains("'@SUM(A1:A9)");
        // The bare form must not survive anywhere in the file.
        assertThat(exported).doesNotContain(",=1+1,");
    }

    @Test
    @DisplayName("the guard added on export is removed again on import")
    void formulaGuardRoundTrips() throws Exception {
        String token = signUp("guarded");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,=1+1,Groceries,5.00,GBP,Cash,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        // Round-tripping must give back what the user wrote, not the guard.
        String other = signUp("unguarded");
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(exported))
                .header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.content[0].description").value("=1+1"));
    }

    @Test
    @DisplayName("a negative amount is not mistaken for a formula")
    void negativeAmountsAreNotGuarded() throws Exception {
        String token = signUp("negative");
        recordExpense(token, categoryId(token, "Groceries"), "Refunded item", "12.00", "2026-08-10");

        String exported = mockMvc.perform(get("/api/transactions/export")
                .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();

        // Guarding numbers would corrupt every figure in the file to buy nothing.
        assertThat(exported).contains(",12.00,");
        assertThat(exported).doesNotContain("'12.00");
    }

    @Test
    @DisplayName("a file without a Type column still imports, as expenses")
    void typeColumnIsOptional() throws Exception {
        String token = signUp("notype");

        // Every file exported before the column existed, and every hand-made
        // one, has seven columns. They must keep working unchanged.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,Coffee,Groceries,3.50,GBP,Cash,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].type").value("EXPENSE"));
    }

    @Test
    @DisplayName("income is read from the Type column and kept out of spending")
    void incomeIsDistinctFromSpending() throws Exception {
        String token = signUp("earner");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments,Type
                        2026-08-10,Weekly shop,Groceries,40.00,GBP,Card,,Expense
                        2026-08-11,Refund for returned item,Groceries,15.00,GBP,Card,,Credit
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        // "Credit" is one of the wordings banks use for the same idea.
        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Refund for returned item"));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("the sign of an amount is not used to guess the direction")
    void signIsNotUsedToInferType() throws Exception {
        String token = signUp("signed");

        // Banks disagree about the convention — some export debits negative,
        // some export everything positive — so guessing would file income as
        // spending for half of them. Absent a Type column, it is an expense.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-10,Card payment,Groceries,-40.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.content[0].amount").value(40.00));
    }

    @Test
    @DisplayName("a bad value in a Type column that is a direction is reported")
    void unknownTypeIsRejected() throws Exception {
        String token = signUp("badtype");

        // The column is claimed as a direction because other rows in it say
        // Expense and Income. Against that, "Sideways" is a mistake worth
        // reporting rather than a payment method.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments,Type
                        2026-08-10,Coffee,Groceries,3.50,GBP,Cash,,Expense
                        2026-08-11,Lunch,Groceries,9.00,GBP,Cash,,Sideways
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors[0]", Matchers.containsString("Sideways")));
    }

    @Test
    @DisplayName("a Type column holding nothing we recognise is read as the payment method")
    void unrecognisedTypeColumnBecomesPaymentMethod() throws Exception {
        String token = signUp("othertype");

        // This is what a bank's "Type" column is: Monzo writes "Card payment",
        // Chase "Sale", Lloyds "DEB". None of them is a direction, and rejecting
        // the rows would reject the statement. The column's own values decide,
        // and here they say payment method.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Type
                        2026-08-10,Coffee,Groceries,3.50,Contactless
                        2026-08-11,Lunch,Groceries,9.00,Chip and PIN
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(get("/api/transactions?from=2026-08-10&to=2026-08-10")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].paymentMethod").value("Contactless"))
                .andExpect(jsonPath("$.content[0].type").value("EXPENSE"));
    }

    @Test
    @DisplayName("a byte-order mark from Excel does not eat the first row")
    void handlesExcelByteOrderMark() throws Exception {
        String token = signUp("excel");

        // Excel writes a BOM at the start of a UTF-8 CSV. It is invisible, and
        // it used to defeat the header check — so the header was parsed as data
        // and every Excel-saved file reported a spurious error on line 1.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("\uFEFF" + """
                        Date,Description,Category,Amount
                        2026-08-10,Coffee,Groceries,3.50
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("a quoted field containing a newline is one record, not two")
    void handlesNewlineInsideQuotedField() throws Exception {
        String token = signUp("multiline");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,"Hotel
                        Paris",Other,120.00,GBP,Card,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Hotel\nParis"));
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
    @DisplayName("import and export are audited as counts, never as content")
    void bulkMovesAreAudited() throws Exception {
        String token = signUp("audited");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,Therapy session,Health,80.00,GBP,Card,private note
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions/export").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        var entries = activityLog.findAll().stream()
                .filter(entry -> "audited".equals(entry.getUsername()))
                .toList();

        assertThat(entries).anyMatch(e -> e.getAction() == ActivityAction.TRANSACTIONS_IMPORTED);
        assertThat(entries).anyMatch(e -> e.getAction() == ActivityAction.TRANSACTIONS_EXPORTED);

        // The audit trail is readable by an administrator. That a ledger moved
        // belongs there; what was in it does not.
        assertThat(entries).noneMatch(e -> e.getDetail() != null
                && (e.getDetail().contains("Therapy") || e.getDetail().contains("private note")
                        || e.getDetail().contains("80.00") || e.getDetail().contains("Health")));
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
