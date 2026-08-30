package com.lokesh_codes.expense_tracker_backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.lokesh_codes.expense_tracker_backend.DTO.StatementPreviewDTO;

/**
 * Shows what the importer makes of a statement, without importing it.
 *
 * <p>When a file will not import, the useful question is what the parser
 * actually saw, and until now the only way to answer it was to add logging and
 * redeploy. This answers it directly: the extracted text, the line taken as the
 * header, and the CSV the importer would go on to read.
 *
 * <p>Nothing is written. No transaction, no category, no audit row — the file is
 * read in memory and dropped, and a preview leaves the database exactly as it
 * found it. That is deliberate: a diagnostic that changes what it is diagnosing
 * is worse than none.
 *
 * <p>Values can be redacted while the spacing is kept. A statement's layout is
 * what makes it parseable and its contents are what make it private, and those
 * are separable: replacing every letter with x and every digit with 9, in place,
 * preserves the first exactly and destroys the second. A redacted preview can be
 * pasted into a bug report or handed to somebody helping, which the real one
 * cannot.
 */
@Service
public class StatementPreviewService {

    /** Enough to see the shape of a statement without returning the whole of it. */
    private static final int MAX_LINES = 400;

    private final PdfStatementReader pdfReader;

    public StatementPreviewService(PdfStatementReader pdfReader) {
        this.pdfReader = pdfReader;
    }

    public StatementPreviewDTO preview(MultipartFile file, String password, boolean redact)
            throws IOException {

        byte[] uploaded = file.getBytes();
        if (uploaded.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That file is empty.");
        }

        List<String> lines = PdfStatementReader.looksLikePdf(uploaded)
                ? pdfReader.readLines(uploaded, password)
                : List.of(new String(uploaded, StandardCharsets.UTF_8).split("\n", -1));

        // Detection runs on the real text. Redacting first would hide the very
        // column names the header is recognised by, and the preview would then
        // describe a file nobody uploaded.
        PdfStatementTable.Extracted table = PdfStatementTable.toCsv(lines);

        List<String> shown = lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;

        if (redact) {
            shown = redactExceptHeader(shown, table.headerLine());
        }

        return new StatementPreviewDTO(
                shown,
                table.headerLine(),
                table.rows(),
                redact ? redactCsvBody(table.csv()) : table.csv(),
                describeColumns(table),
                redact);
    }

    /**
     * Replaces values but not the header, and not the spacing.
     *
     * <p>The header is left alone because it is the one line that has to be read
     * literally to know whether a column name is recognised — and it names
     * columns rather than saying anything about the account.
     */
    private List<String> redactExceptHeader(List<String> lines, int headerLine) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(i == headerLine ? lines.get(i) : redact(lines.get(i)));
        }
        return out;
    }

    /** Keeps the CSV's first line — the header — and redacts the rows under it. */
    private String redactCsvBody(String csv) {
        if (csv.isBlank()) {
            return csv;
        }
        String[] rows = csv.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            out.append(i == 0 ? rows[i] : redact(rows[i]));
            if (i < rows.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Letters become x, digits become 9, everything else stays where it is.
     *
     * <p>Character-for-character, so column positions, field widths and the gaps
     * between them are all unchanged. What is lost is only what the characters
     * said.
     */
    private String redact(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (char c : line.toCharArray()) {
            if (Character.isLetter(c)) {
                out.append(Character.isUpperCase(c) ? 'X' : 'x');
            } else if (Character.isDigit(c)) {
                out.append('9');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String describeColumns(PdfStatementTable.Extracted table) {
        if (table.headerLine() < 0) {
            return "No header row was recognised. The importer looks for a line naming at least "
                    + "two of: a date, a description, an amount, or separate debit and credit "
                    + "columns.";
        }
        if (table.rows() == 0) {
            return "A header was found, but no line beneath it began with a date.";
        }

        List<String> header = CsvSupport.parseLine(table.csv().split("\n", 2)[0]);
        CsvColumns columns = CsvColumns.fromHeader(header, List.of());
        return columns.summary();
    }
}
