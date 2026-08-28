package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

/**
 * Outcome of a CSV import.
 *
 * <p>Rows are reported individually rather than failing the whole file, so one
 * malformed line does not discard a valid statement.
 *
 * @param imported  rows written, including any that were flagged
 * @param skipped   rows that could not be read at all
 * @param flagged   rows written that match a transaction already on file. These
 *                  are part of {@code imported}, not separate from it: they are
 *                  real rows and they count toward totals until the owner
 *                  reviews them.
 * @param errors    the first few reasons a row was skipped
 * @param columnMapping how the file's columns were understood, in plain words.
 *                  Always reported: a mapping that guessed wrong is worse than
 *                  one that failed, and the user is the only one who can see
 *                  that "Reference" was the wrong column to read as the
 *                  description.
 */
public record ImportResultDTO(int imported, int skipped, int flagged, List<String> errors,
        String columnMapping) {
}
