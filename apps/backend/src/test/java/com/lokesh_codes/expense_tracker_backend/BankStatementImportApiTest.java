package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Statements as banks actually export them.
 *
 * <p>Every layout here is one that imported <em>nothing at all</em> before
 * column mapping existed: the importer expected
 * {@code Date,Description,Category,Amount} by position, and none of these are
 * that. They are kept as whole files rather than reduced to unit cases because
 * the thing that broke was never one field — it was the shape of the file.
 */
class BankStatementImportApiTest extends ApiTestBase {

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions importFile(String token, String content) throws Exception {
        return mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(content))
                .header("Authorization", bearer(token)));
    }

    private ResultActions importFile(String token, String content, String dateOrder)
            throws Exception {
        return mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(content))
                .param("dateOrder", dateOrder)
                .header("Authorization", bearer(token)));
    }

    @Test
    @DisplayName("HSBC: three columns, no header, debits negative")
    void hsbc() throws Exception {
        String token = signUp("hsbc");

        importFile(token, """
                14/08/2026,TESCO STORES 3421,-42.75
                15/08/2026,SALARY ACME LTD,2400.00
                16/08/2026,TFL TRAVEL CHARGE,-6.40
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.skipped").value(0));

        // The file has both signs, so the sign is carrying the direction.
        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("SALARY ACME LTD"));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("Lloyds: money split across separate debit and credit columns")
    void lloyds() throws Exception {
        String token = signUp("lloyds");

        importFile(token, """
                Transaction Date,Transaction Type,Sort Code,Account Number,Transaction Description,Debit Amount,Credit Amount,Balance
                14/08/2026,DEB,'30-00-00,12345678,TESCO STORES 3421,42.75,,1200.00
                15/08/2026,BGC,'30-00-00,12345678,SALARY ACME LTD,,2400.00,3600.00
                """)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.columnMapping",
                        Matchers.containsString("Transaction Description")));

        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(2400.00));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].amount").value(42.75));
    }

    @Test
    @DisplayName("Monzo: sixteen columns, a transaction id first, and a Type that is not a direction")
    void monzo() throws Exception {
        String token = signUp("monzo");

        importFile(token, """
                Transaction ID,Date,Time,Type,Name,Emoji,Category,Amount,Currency,Local amount,Local currency,Notes and #tags,Address,Receipt,Description,Category split
                tx_001,14/08/2026,09:12:00,Card payment,Tesco,,Groceries,-42.75,GBP,-42.75,GBP,,,,TESCO STORES 3421,
                tx_002,15/08/2026,08:00:00,Faster payment,Acme Ltd,,Income,2400.00,GBP,2400.00,GBP,,,,SALARY,
                """)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        // "Type" here holds "Card payment", not a direction, so it is read as the
        // payment method and the sign decides the direction instead.
        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].paymentMethod").value("Card payment"))
                .andExpect(jsonPath("$.content[0].currency").value("GBP"))
                // Monzo sends "Name" ("Tesco") before "Description"
                // ("TESCO STORES 3421"). The fuller text is what a filing rule
                // is written against, so the better-named column wins over the
                // earlier one.
                .andExpect(jsonPath("$.content[0].description").value("TESCO STORES 3421"));

        // "Amount" pairs with "Currency"; "Local amount" pairs with "Local
        // currency" and holds the merchant's figure on a foreign purchase.
        // Taking the wrong one labels the wrong number with the wrong currency.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("SALARY"));
    }

    @Test
    @DisplayName("Chase: US column names, and a Category column that is not fourth")
    void chase() throws Exception {
        String token = signUp("chase");

        importFile(token, """
                Transaction Date,Post Date,Description,Category,Type,Amount,Memo
                03/04/2026,03/05/2026,WHOLE FOODS MKT,Groceries,Sale,-52.18,
                03/07/2026,03/08/2026,PAYROLL DEPOSIT,Income,Payment,3100.00,
                """, "MONTH_FIRST")
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        // Read month-first, 03/04/2026 is the 4th of March.
        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].date").value("2026-03-04"))
                .andExpect(jsonPath("$.content[0].categoryName").value("Groceries"));
    }

    @Test
    @DisplayName("HDFC: two-digit years, 'Narration', and withdrawal/deposit columns")
    void hdfc() throws Exception {
        String token = signUp("hdfc");

        importFile(token, """
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                14/08/26,UPI-SWIGGY-1234,000000,14/08/26,450.00,,25000.00
                15/08/26,SALARY CREDIT,000000,15/08/26,,85000.00,110000.00
                """)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0))
                // Both "Date" and "Value Dt" are present. The value date is when
                // the bank settled, not when the money was spent, so the plain
                // one has to win.
                .andExpect(jsonPath("$.columnMapping",
                        Matchers.containsString("Date ← \"Date\"")));

        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("SALARY CREDIT"))
                .andExpect(jsonPath("$.content[0].date").value("2026-08-15"));
    }

    @Test
    @DisplayName("Starling: the currency is named in the amount column's header")
    void starlingCurrencyFromHeader() throws Exception {
        String token = signUp("starling");

        // No currency column at all. Without reading the header, every row would
        // be filed as dollars, which is the default.
        importFile(token, """
                Date,Counter Party,Reference,Type,Amount (GBP),Balance (GBP),Spending Category,Notes
                14/08/2026,Tesco,TESCO STORES 3421,FASTER PAYMENT,-42.75,1200.00,Groceries,
                """)
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].currency").value("GBP"));
    }

    @Test
    @DisplayName("a file of expenses only is not read as income just because it is all positive")
    void allPositiveMeansAllExpense() throws Exception {
        String token = signUp("positive");

        // The sign only carries meaning when the file shows both. A statement of
        // nothing but outgoings, all written positive, is not a year of income.
        importFile(token, """
                Date,Description,Amount
                14/08/2026,Tesco,42.75
                15/08/2026,Bus fare,2.40
                """)
                .andExpect(jsonPath("$.imported").value(2));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("the mapping is reported, so a wrong guess is visible")
    void mappingIsReported() throws Exception {
        String token = signUp("mapped");

        importFile(token, """
                Date,Narration,Withdrawal Amt.
                14/08/2026,Tesco,42.75
                """)
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Date")))
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Narration")))
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Withdrawal Amt.")));
    }

    @Test
    @DisplayName("a header we understand but cannot use says what is missing")
    void unusableHeaderIsRefused() throws Exception {
        String token = signUp("unusable");

        // Two names are recognised, so this is read as a header — but none of
        // them is a date, a description or an amount, and no row can be built
        // from what is left. Naming what was found beats importing rubbish.
        importFile(token, """
                Category,Notes,Balance
                Groceries,a note,1200.00
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0]",
                        Matchers.containsString("Could not find a date")))
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Category")));
    }

    @Test
    @DisplayName("a file with no recognisable header falls back to reading by position")
    void unrecognisedHeaderFallsBackToPositions() throws Exception {
        String token = signUp("noheader");

        // Only one name here is recognised, which is below the threshold for
        // trusting it as a header. The old positional reading takes over, and
        // says plainly that the first column is not a date.
        importFile(token, """
                Reference,Balance,Sort Code
                abc,1200.00,30-00-00
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0]",
                        Matchers.containsString("is not a date we recognise")));
    }

    @Test
    @DisplayName("dates that contradict the chosen order are reported, not filed silently")
    void contradictoryDateOrderIsReported() throws Exception {
        String token = signUp("contradiction");

        // 14/08 can only be day-first; there is no fourteenth month. Told to read
        // month-first, the import must say so rather than quietly shifting every
        // ambiguous date in the file.
        importFile(token, """
                Date,Description,Amount
                14/08/2026,Tesco,42.75
                03/04/2026,Boots,10.00
                """, "MONTH_FIRST")
                .andExpect(jsonPath("$.errors", Matchers.hasItem(
                        Matchers.containsString("look like day first"))));
    }

    @Test
    @DisplayName("our own export still round-trips, header and all")
    void ourOwnExportStillWorks() throws Exception {
        String token = signUp("roundtrip");

        importFile(token, """
                Date,Description,Category,Amount,Currency,Payment Method,Comments,Type
                2026-08-10,Weekly shop,Groceries,42.75,GBP,Card,,Expense
                2026-08-11,Refund,Groceries,15.00,GBP,Card,,Income
                """)
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        // "Type" holding Expense/Income is read as a direction here, unlike
        // Monzo's, because of what is in the column rather than what it is called.
        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].description").value("Refund"));
    }
}
