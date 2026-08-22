package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

/**
 * Outcome of a CSV import.
 *
 * <p>Rows are reported individually rather than failing the whole file, so one
 * malformed line does not discard a valid statement.
 */
public record ImportResultDTO(int imported, int skipped, List<String> errors) {
}
