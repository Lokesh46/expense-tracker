package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import static org.assertj.core.api.Assertions.assertThat;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Importing a statement that arrives as a PDF, including a locked one.
 *
 * <p>The PDFs here are built rather than checked in, so the test says out loud
 * what layout it assumes: a monospaced table with columns separated by runs of
 * spaces, which is what a bank statement looks like once the text is extracted
 * with its positions preserved.
 *
 * <p>That is also the limit of what this proves. It shows the pipeline works --
 * decryption, extraction, table recovery, and the whole existing CSV path
 * behind it -- against a layout modelled on HDFC's. It cannot show that a
 * particular bank's real spacing survives, and no synthetic fixture can.
 */
class PdfStatementImportApiTest extends ApiTestBase {

    /** Columns padded to fixed widths, the way a statement PDF draws a table. */
    private static final List<String> HDFC_LINES = List.of(
            "HDFC BANK LIMITED",
            "Statement of account",
            "",
            "Date        Narration                  Chq./Ref.No.    Withdrawal Amt.    Deposit Amt.    Closing Balance",
            "14/08/26    UPI-SWIGGY-1234            000000          450.00                             25000.00",
            "15/08/26    SALARY CREDIT              000000                             85000.00        110000.00",
            "16/08/26    UPI-UBER-9911              000000          320.50                             109679.50",
            "",
            "*** End of statement ***");

    private byte[] pdf(List<String> lines, String password) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                // Monospaced, so the column gaps survive as gaps rather than
                // being closed up by proportional letter widths.
                content.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 7);
                content.setLeading(12);
                content.newLineAtOffset(20, 750);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }

            if (password != null) {
                AccessPermission permissions = new AccessPermission();
                document.protect(new StandardProtectionPolicy(password + "-owner", password,
                        permissions));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "statement.pdf", "application/pdf", bytes);
    }

    private ResultActions importPdf(String token, byte[] bytes, String password) throws Exception {
        var request = multipart("/api/transactions/import")
                .file(upload(bytes))
                .param("defaultCurrency", "INR")
                .header("Authorization", bearer(token));
        if (password != null) {
            request = request.param("pdfPassword", password);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("an unprotected statement PDF imports like the CSV it contains")
    void plainPdfImports() throws Exception {
        String token = signUp("pdfplain");

        importPdf(token, pdf(HDFC_LINES, null), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.skipped").value(0))
                // The column names come from the PDF's own header, through the
                // same alias table the CSV path uses.
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Narration")));

        // Withdrawal and Deposit columns still mean money out and money in.
        mockMvc.perform(get("/api/transactions?type=INCOME")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("SALARY CREDIT"))
                .andExpect(jsonPath("$.content[0].currency").value("INR"));

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a password-protected statement imports when given its password")
    void protectedPdfImports() throws Exception {
        String token = signUp("pdflocked");

        importPdf(token, pdf(HDFC_LINES, "s3cret"), "s3cret")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3));
    }

    @Test
    @DisplayName("a protected statement with no password says so, rather than failing obscurely")
    void protectedPdfWithoutPasswordIsExplained() throws Exception {
        String token = signUp("pdfnopass");

        importPdf(token, pdf(HDFC_LINES, "s3cret"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        Matchers.containsString("password protected")));
    }

    @Test
    @DisplayName("a wrong password is distinguished from a missing one")
    void wrongPasswordIsItsOwnMessage() throws Exception {
        String token = signUp("pdfbadpass");

        importPdf(token, pdf(HDFC_LINES, "s3cret"), "not-it")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        Matchers.containsString("did not open")));
    }

    @Test
    @DisplayName("a PDF with no recognisable table is refused rather than reported as empty")
    void pdfWithoutATableIsRefused() throws Exception {
        String token = signUp("pdfnotable");

        // A letter from the bank, not a statement. Importing nothing and calling
        // it success would read as "you had no transactions".
        byte[] letter = pdf(List.of(
                "HDFC BANK LIMITED",
                "Dear customer,",
                "Your cheque book request has been processed.",
                "Regards"), null);

        importPdf(token, letter, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0]",
                        Matchers.containsString("No transaction table")));
    }

    @Test
    @DisplayName("a scanned statement is named as a scan rather than imported as nothing")
    void scannedPdfIsRefused() throws Exception {
        String token = signUp("pdfscan");

        // A page with almost no extractable text is what a photographed or
        // scanned statement looks like from here.
        byte[] scan = pdf(List.of("."), null);

        importPdf(token, scan, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("scan")));
    }

    @Test
    @DisplayName("the admin preview describes a statement without importing it")
    void previewDescribesWithoutImporting() throws Exception {
        String admin = signUpAdmin("pdfpeek");

        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, "s3cret")))
                .param("pdfPassword", "s3cret")
                .param("redact", "false")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsDetected").value(3))
                .andExpect(jsonPath("$.headerLine").value(Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.columnMapping", Matchers.containsString("Narration")));

        // A diagnostic that changed what it was diagnosing would be worse than
        // none, so nothing was written.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("redaction keeps the header and the column positions, and loses the values")
    void redactionKeepsTheShapeAndDropsTheContents() throws Exception {
        String admin = signUpAdmin("pdfredact");

        String body = mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redacted").value(true))
                .andReturn().getResponse().getContentAsString();

        var preview = parse(body);
        int headerLine = preview.get("headerLine").asInt();
        var lines = preview.get("lines");

        // The header survives: it names the columns rather than the account, and
        // it is the line that has to be read literally to know whether a name is
        // recognised.
        assertThat(lines.get(headerLine).asText()).contains("Narration");

        // The values do not.
        String allText = body;
        assertThat(allText).doesNotContain("SWIGGY");
        assertThat(allText).doesNotContain("85000.00");

        // But their shape does, character for character -- which is what makes a
        // redacted preview useful to somebody debugging the layout.
        String salary = lines.get(headerLine + 2).asText();
        assertThat(salary).contains("99/99/99");
        assertThat(salary).contains("XXXXXX XXXXXX");
    }

    @Test
    @DisplayName("a redacted preview can be collected from another device, and an unredacted one cannot")
    void onlyRedactedPreviewsAreHeldForCollection() throws Exception {
        String admin = signUpAdmin("pdfcollect");

        // Nothing waiting to begin with.
        mockMvc.perform(get("/api/admin/statement/last")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // Redacted, so it is held: the upload can happen on a phone and the
        // reading somewhere else.
        mockMvc.perform(get("/api/admin/statement/last")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsDetected").value(3))
                .andExpect(jsonPath("$.redacted").value(true));

        // An unredacted preview holds real values, so it is returned once and
        // not kept -- and it clears whatever was being held.
        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .param("redact", "false")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/statement/last")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a held preview can be discarded before it expires")
    void heldPreviewCanBeDiscarded() throws Exception {
        String admin = signUpAdmin("pdfdiscard");

        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/admin/statement/last")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/statement/last")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("one administrator cannot collect another's preview")
    void heldPreviewsAreNotShared() throws Exception {
        String first = signUpAdmin("pdfadmin-a");
        String second = signUpAdmin("pdfadmin-b");

        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .header("Authorization", bearer(first)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/statement/last")
                .header("Authorization", bearer(second)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the preview is for administrators only")
    void previewIsAdminOnly() throws Exception {
        String member = signUp("pdfmember");

        mockMvc.perform(multipart("/api/admin/statement/preview")
                .file(upload(pdf(HDFC_LINES, null)))
                .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("filing rules and duplicate detection apply to a PDF import too")
    void pdfGoesThroughTheSamePipeline() throws Exception {
        String token = signUp("pdfpipeline");

        byte[] statement = pdf(HDFC_LINES, null);
        importPdf(token, statement, null).andExpect(jsonPath("$.imported").value(3));

        // The same statement again: the fingerprints match, so every row is
        // flagged -- exactly as it would be for the CSV of the same data.
        importPdf(token, statement, null)
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.flagged").value(3));
    }
}
