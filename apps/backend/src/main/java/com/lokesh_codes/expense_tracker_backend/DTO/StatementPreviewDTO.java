package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

/**
 * What the importer sees in a statement, without importing it.
 *
 * <p>A diagnostic. Nothing here is stored: the file is read in memory, described,
 * and dropped. It exists so that a statement which will not import can be
 * looked at, rather than guessed about.
 *
 * @param lines        the extracted text, one entry per line of the page, with
 *                     spacing preserved — spacing is the table
 * @param headerLine   index into {@code lines} of the row taken as the header,
 *                     or -1 when none was recognised
 * @param rowsDetected how many lines looked like transactions
 * @param csv          the table rewritten as CSV, which is what the importer
 *                     would actually read
 * @param columnMapping how those columns were understood, in plain words
 * @param redacted     whether values have been replaced with placeholders. The
 *                     shape of the file survives redaction; the contents do not,
 *                     which is what makes a redacted preview safe to send to
 *                     somebody else for help.
 */
public record StatementPreviewDTO(
        List<String> lines,
        int headerLine,
        int rowsDetected,
        String csv,
        String columnMapping,
        boolean redacted) {
}
