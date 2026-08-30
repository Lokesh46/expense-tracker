package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recovers a statement's table from the text of a PDF page.
 *
 * <p>The output is CSV, deliberately. Everything the importer already knows —
 * that "Narration" is a description and "Withdrawal Amt." is money going out,
 * how to read an ambiguous date, which filing rule applies, whether a row is a
 * duplicate — is reached through the CSV path. Turning the table back into CSV
 * means a PDF import is the same import, and none of that has to be written a
 * second time or kept in step.
 *
 * <p><strong>Columns are found by position, not by splitting on whitespace.</strong>
 * Splitting on runs of spaces is the obvious approach and it is wrong, because
 * an empty cell leaves nothing to split on: a row with no withdrawal produces
 * one field fewer, every later value shifts left, and a salary credit is
 * imported as a purchase. It fails silently and it fails on exactly the rows
 * that matter.
 *
 * <p>So the separators are found instead: a column boundary is a run of
 * character positions that is blank on <em>every</em> line of the table at once,
 * header included. That survives empty cells, and it survives amounts being
 * right-aligned under a left-aligned heading, which character offsets taken from
 * the header alone do not.
 */
final class PdfStatementTable {

    /**
     * A row of the table begins with a date. Loose on purpose — this only has to
     * tell a transaction line apart from a page header or a running total, and
     * the real parsing happens later against the chosen date order.
     */
    private static final Pattern STARTS_WITH_DATE = Pattern.compile(
            "^\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b.*");

    /** Narrower than this and a gap is more likely to be inside a cell than between two. */
    private static final int MIN_SEPARATOR_WIDTH = 2;

    private PdfStatementTable() {
    }

    /** What was recovered, and enough about it to explain a disappointing result. */
    record Extracted(String csv, int headerLine, int rows) {
        boolean isEmpty() {
            return rows == 0;
        }
    }

    /**
     * Finds the table and rewrites it as CSV.
     *
     * <p>Statements repeat their header on every page and pad the gaps with
     * addresses, balances and marketing. Only the first header is kept, and only
     * lines that start with a date are taken as rows, which discards the rest
     * without needing to know what any of it was.
     */
    static Extracted toCsv(List<String> lines) {
        int headerLine = findHeader(lines);
        if (headerLine < 0) {
            return new Extracted("", -1, 0);
        }

        List<String> body = new ArrayList<>();
        for (int i = headerLine + 1; i < lines.size(); i++) {
            if (STARTS_WITH_DATE.matcher(lines.get(i)).matches()) {
                body.add(lines.get(i));
            }
        }
        if (body.isEmpty()) {
            return new Extracted("", headerLine, 0);
        }

        // The header has to take part in finding the separators: a column whose
        // cells are all empty would otherwise have no boundary of its own, and
        // its heading would be swallowed into a neighbour.
        List<String> table = new ArrayList<>();
        table.add(lines.get(headerLine));
        table.addAll(body);

        List<int[]> spans = columnSpans(table);

        StringBuilder csv = new StringBuilder();
        for (String line : table) {
            csv.append(CsvSupport.row(slice(line, spans).toArray()));
        }
        return new Extracted(csv.toString(), headerLine, body.size());
    }

    /**
     * The character ranges the columns occupy.
     *
     * <p>A position belongs to a separator when every line of the table is blank
     * there. Runs of such positions are the gaps between columns; what lies
     * between two gaps is a column.
     */
    private static List<int[]> columnSpans(List<String> table) {
        int width = table.stream().mapToInt(String::length).max().orElse(0);

        boolean[] blank = new boolean[width];
        for (int column = 0; column < width; column++) {
            blank[column] = true;
            for (String line : table) {
                if (column < line.length() && !Character.isWhitespace(line.charAt(column))) {
                    blank[column] = false;
                    break;
                }
            }
        }

        List<int[]> spans = new ArrayList<>();
        int start = -1;
        int blankRun = 0;

        for (int column = 0; column <= width; column++) {
            boolean isBlank = column == width || blank[column];

            if (isBlank) {
                blankRun++;
                // The column ends once the gap is wide enough to be a separator
                // rather than a space inside a cell.
                if (start >= 0 && blankRun == MIN_SEPARATOR_WIDTH) {
                    spans.add(new int[] { start, column - MIN_SEPARATOR_WIDTH + 1 });
                    start = -1;
                }
            } else {
                blankRun = 0;
                if (start < 0) {
                    start = column;
                }
            }
        }
        if (start >= 0) {
            spans.add(new int[] { start, width });
        }

        return spans;
    }

    /** The line's text within each column range, trimmed. An empty range stays empty. */
    private static List<String> slice(String line, List<int[]> spans) {
        List<String> fields = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            int from = Math.min(span[0], line.length());
            int to = Math.min(span[1], line.length());
            fields.add(line.substring(from, to).strip());
        }
        return fields;
    }

    /**
     * The first line that names at least two columns the importer understands.
     *
     * <p>Reuses the importer's own alias table rather than keeping a second
     * list: a bank that calls it "Narration" should be recognised here for
     * exactly the reason it is recognised there.
     */
    private static int findHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            List<String> fields = new ArrayList<>();
            for (String part : lines.get(i).strip().split(" {2,}")) {
                fields.add(part.strip());
            }
            if (fields.size() >= 3 && CsvColumns.looksLikeHeader(fields)) {
                return i;
            }
        }
        return -1;
    }
}
