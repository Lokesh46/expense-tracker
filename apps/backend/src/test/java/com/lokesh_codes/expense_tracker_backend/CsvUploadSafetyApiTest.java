package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import jakarta.servlet.MultipartConfigElement;

/**
 * What the import endpoint refuses, and what it promises about the file itself.
 *
 * <p>The allowance is lowered here so the limit can be reached in three
 * requests rather than six. Its own context, so the limiter starts empty and
 * cannot inherit counts from another class.
 */
@TestPropertySource(properties = {
        "app.csv.max-imports-per-hour=2",
        "app.csv.max-exports-per-hour=2"
})
class CsvUploadSafetyApiTest extends ApiTestBase {

    @Autowired
    private MultipartConfigElement multipartConfig;

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", content);
    }

    private MockMultipartFile csv(String content) {
        return file(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an uploaded statement is never spilled to disk")
    void uploadsAreHeldInMemory() {
        // This is the whole of the "we scan it and it is gone" guarantee. A
        // servlet container writes a part to a temp file as soon as it exceeds
        // the threshold, and the framework default is 0 bytes — meaning every
        // upload was written out before any of our code ran. Holding the
        // threshold at the largest size accepted is what keeps the part in
        // memory for its whole life.
        //
        // Asserted against the resolved configuration rather than by watching a
        // directory: the temp file, if one were written, is deleted when the
        // request ends, so counting files afterwards cannot tell the two cases
        // apart. This is the invariant that decides the behaviour, and it is
        // the one someone breaks by raising max-file-size on its own.
        assertThat((long) multipartConfig.getFileSizeThreshold())
                .as("multipart threshold must not be below the largest accepted upload, "
                        + "or the container writes the file to disk")
                .isGreaterThanOrEqualTo(multipartConfig.getMaxFileSize());
    }

    @Test
    @DisplayName("an empty file is refused rather than reported as nothing imported")
    void rejectsEmptyFile() throws Exception {
        String token = signUp("emptyfile");

        mockMvc.perform(multipart("/api/transactions/import")
                .file(file(new byte[0]))
                .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That file is empty."));
    }

    @Test
    @DisplayName("a binary file renamed .csv is refused before it is parsed")
    void rejectsBinaryContent() throws Exception {
        String token = signUp("binaryfile");

        // A NUL byte cannot occur in UTF-8 text. Without this check the parser
        // grinds through the whole file and answers with thousands of errors,
        // which tells the user nothing about what actually went wrong.
        byte[] binary = new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08, 0x00 };

        mockMvc.perform(multipart("/api/transactions/import")
                .file(file(binary))
                .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("That does not look like a CSV file. Export your statement as CSV and try again."));
    }

    @Test
    @DisplayName("importing past the hourly allowance is refused with 429")
    void importsAreRateLimited() throws Exception {
        String token = signUp("eager");

        String body = """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,Coffee,Groceries,3.50,GBP,Cash,
                """;

        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(multipart("/api/transactions/import")
                    .file(csv(body))
                    .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(body))
                .header("Authorization", bearer(token)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("files that could not be read do not spend the allowance")
    void failedImportsAreNotCharged() throws Exception {
        String token = signUp("unlucky");

        // Three distinct unreadable files -- distinct so each is genuinely
        // charged and then handed back, rather than being waved through as a
        // repeat of one the limiter had already seen.
        for (int attempt = 1; attempt <= 3; attempt++) {
            byte[] binary = new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08, (byte) attempt };

            mockMvc.perform(multipart("/api/transactions/import")
                    .file(file(binary))
                    .header("Authorization", bearer(token)))
                    .andExpect(status().isBadRequest());
        }

        // The allowance is two, so under the old behaviour this is a 429 for an
        // account that has not managed to import a single row -- and, because
        // only successful imports reach the activity log, with nothing anywhere
        // to tell them why.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-10,Coffee,Groceries,3.50,GBP,Cash,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    @DisplayName("a file that imports nothing does not spend the allowance")
    void filesThatImportNothingAreNotCharged() throws Exception {
        String token = signUp("mislabelled");

        // The commonest real failure, and the one that decided where the line
        // goes. A header the reader does not recognise is not refused: it falls
        // back to reading columns by position, so the file is parsed in full and
        // then every row fails to be a date. It answers 200 with an explanation
        // rather than an error status, which is exactly why charging for it went
        // unnoticed for so long.
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(multipart("/api/transactions/import")
                    .file(csv("Alpha,Beta,Gamma\nx" + attempt + ",y,z\n"))
                    .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(0))
                    .andExpect(jsonPath("$.skipped").value(2));
        }

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount,Currency,Payment Method,Comments
                        2026-08-11,Bus,Travel,2.40,GBP,Cash,
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    @DisplayName("one account's allowance is its own")
    void limitIsPerAccount() throws Exception {
        String busy = signUp("busy");
        String quiet = signUp("quiet");

        String body = """
                Date,Description,Category,Amount,Currency,Payment Method,Comments
                2026-08-10,Coffee,Groceries,3.50,GBP,Cash,
                """;

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(multipart("/api/transactions/import")
                    .file(csv(body))
                    .header("Authorization", bearer(busy)));
        }

        // Exhausting one account must not lock out everybody else on the instance.
        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv(body))
                .header("Authorization", bearer(quiet)))
                .andExpect(status().isOk());
    }
}
