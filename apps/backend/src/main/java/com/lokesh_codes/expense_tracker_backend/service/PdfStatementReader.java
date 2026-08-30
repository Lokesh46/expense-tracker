package com.lokesh_codes.expense_tracker_backend.service;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a statement PDF into the text of its table.
 *
 * <p>Only the reading is here. What the text <em>means</em> — which run of
 * characters is a date and which is an amount — belongs to
 * {@code PdfStatementTable}, because that part is about the shape of a
 * particular bank's statement and this part is not.
 *
 * <p>The file is decrypted in memory and never written anywhere, the same
 * guarantee the CSV path already gives: the multipart threshold in
 * {@code application.properties} keeps the upload out of the servlet
 * container's temp directory, and nothing here puts it back.
 *
 * <p>The password is held only for the length of the call that opens the
 * document. It is not stored, not logged, and deliberately kept out of the
 * audit trail — a bank statement password in a table an administrator can read
 * would be worse than the encryption it unlocks.
 */
@Component
public class PdfStatementReader {

    /** Every PDF begins with this, whatever the browser called the upload. */
    private static final byte[] MAGIC = { '%', 'P', 'D', 'F', '-' };

    /**
     * Below this much text, a page is taken to be a scan rather than a
     * statement. A real page of transactions runs to hundreds of characters; a
     * photographed one extracts a handful of stray marks, or nothing.
     */
    private static final int MIN_CHARS_PER_PAGE = 40;

    /** Bounded because a statement is a few pages and a decompression bomb is not. */
    private static final int MAX_PAGES = 200;

    /** Whether these bytes are a PDF, judged by what they are rather than what they claim. */
    public static boolean looksLikePdf(byte[] content) {
        if (content.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (content[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts the document's text, one line per line of the page, with the
     * horizontal spacing kept.
     *
     * <p>Spacing is the whole point. A statement is a table drawn with
     * whitespace, and collapsing it would destroy the only evidence of where one
     * column ends and the next begins.
     *
     * @param password the document's open password, or null when it has none
     */
    public List<String> readLines(byte[] content, String password) {
        try (PDDocument document = open(content, password)) {

            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That PDF has " + document.getNumberOfPages()
                                + " pages. Export a single statement rather than a whole archive.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // Sorting by position is what recovers a table from a PDF: without
            // it the text comes back in the order it happens to be drawn, which
            // for a table is close to arbitrary.
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");

            String text = stripper.getText(document);
            rejectIfScanned(text, document.getNumberOfPages());

            return List.of(text.split("\n", -1));

        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That PDF could not be read. If it opens in a PDF viewer, try re-saving it "
                            + "and uploading again.");
        }
    }

    private PDDocument open(byte[] content, String password) throws IOException {
        try {
            return Loader.loadPDF(content, password == null ? "" : password);
        } catch (InvalidPasswordException e) {
            // Distinguishes the two cases the user actually cares about, because
            // "could not open" leaves them guessing which one they are in.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    password == null || password.isBlank()
                            ? "That PDF is password protected. Enter its password and try again."
                            : "That password did not open the PDF.");
        }
    }

    /**
     * Refuses a PDF that is a picture of a statement rather than a statement.
     *
     * <p>Importing nothing and reporting success would be the worst outcome
     * here: it looks like an empty statement rather than an unreadable one.
     */
    private void rejectIfScanned(String text, int pages) {
        String meaningful = text.replaceAll("\\s", "");
        if (meaningful.length() < MIN_CHARS_PER_PAGE * pages) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That PDF has almost no text in it, which usually means it is a scan or a "
                            + "photograph. Download the statement from your bank as a PDF or CSV "
                            + "rather than scanning a printed copy.");
        }
    }
}
