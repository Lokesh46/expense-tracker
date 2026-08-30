package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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
 * <p>Two ways of finding the columns, because real statements come in both
 * shapes.
 *
 * <p><strong>By position</strong>, when the table is genuinely aligned: a column
 * boundary is a run of character positions blank on every line at once, header
 * included. This is the only reading that can see an <em>empty</em> cell, so it
 * is tried first.
 *
 * <p><strong>By token</strong>, when it is not. A real HDFC statement drifts by
 * several characters from row to row and has at least one line where the
 * narration touches the reference with a single space, so no position is blank
 * throughout and the positional reading collapses seven columns into four.
 * Falling back: the first column is a date, the last few are references, dates
 * and amounts, and none of those contain a space — so the columns can be counted
 * in from both ends and whatever remains in the middle is the description. Which
 * to use is decided by whether the positional reading found as many columns as
 * the header has.
 */
final class PdfStatementTable {

    /**
     * A row of the table begins with a date. Loose on purpose — this only has to
     * tell a transaction line apart from a page header or a running total, and
     * the real parsing happens later against the chosen date order.
     */
    private static final Pattern STARTS_WITH_DATE = Pattern.compile(
            "^\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b.*");

    /** A field of the header: text with its own internal spaces, ending at a real gap. */
    private static final Pattern HEADER_FIELD = Pattern.compile("\\S(?:.*?\\S)?(?=\\s{2,}|$)");

    /** Narrower than this and a gap is more likely to be inside a cell than between two. */
    private static final int MIN_SEPARATOR_WIDTH = 2;

    /**
     * How full a continuation line must be, as a fraction of the description
     * column, to be read as wrapped text rather than a leftover.
     *
     * <p>Text wraps because it filled the width, so a wrapped line is nearly as
     * wide as its column and the remainder that follows it is short. That is the
     * difference between the second line of one transaction's narration and the
     * tail end of the previous one's, and getting it wrong puts one
     * transaction's merchant into another's description — where a filing rule
     * would match it.
     */
    private static final double WRAPPED_LINE_FILL = 0.5;

    /** How far left of its heading a wrapped line may begin and still belong to it. */
    private static final int COLUMN_INDENT_TOLERANCE = 8;

    private PdfStatementTable() {
    }

    /** What was recovered, and enough about it to explain a disappointing result. */
    record Extracted(String csv, int headerLine, int rows) {
        boolean isEmpty() {
            return rows == 0;
        }
    }

    /** A header column: its name, and where across the line it starts. */
    private record HeaderField(int start, String name) {
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

        List<HeaderField> header = parseHeader(lines.get(headerLine));
        List<String> names = header.stream().map(HeaderField::name).toList();

        List<String> body = new ArrayList<>();
        for (int i = headerLine + 1; i < lines.size(); i++) {
            if (STARTS_WITH_DATE.matcher(lines.get(i)).matches()) {
                body.add(lines.get(i));
            }
        }
        if (body.isEmpty()) {
            return new Extracted("", headerLine, 0);
        }

        List<List<String>> rows = readByPosition(lines.get(headerLine), body, names.size());
        if (rows == null) {
            rows = readByToken(lines, headerLine, header);
        }

        StringBuilder csv = new StringBuilder(CsvSupport.row(names.toArray()));
        for (List<String> row : rows) {
            csv.append(CsvSupport.row(row.toArray()));
        }
        return new Extracted(csv.toString(), headerLine, rows.size());
    }

    // ------------------------------------------------------------ by position

    /**
     * Slices every line at the boundaries where the whole table is blank.
     *
     * @return the rows, or null when this reading found fewer columns than the
     *         header has — which means the table is not aligned and the caller
     *         should count tokens in from the ends instead
     */
    private static List<List<String>> readByPosition(String headerLine, List<String> body,
            int expectedColumns) {

        List<String> table = new ArrayList<>();
        table.add(headerLine);
        table.addAll(body);

        List<int[]> spans = columnSpans(table);
        if (spans.size() != expectedColumns) {
            return null;
        }

        List<List<String>> rows = new ArrayList<>(body.size());
        for (String line : body) {
            rows.add(slice(line, spans));
        }
        return rows;
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

    private static List<String> slice(String line, List<int[]> spans) {
        List<String> fields = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            int from = Math.min(span[0], line.length());
            int to = Math.min(span[1], line.length());
            fields.add(line.substring(from, to).strip());
        }
        return fields;
    }

    // --------------------------------------------------------------- by token

    /**
     * Counts columns in from both ends and treats the middle as the description.
     *
     * <p>Every column except the description holds a single word — a date, a
     * reference, an amount — so their tokens can be taken from the left and the
     * right without knowing where they sit. Only the description can contain a
     * space, and it is whatever is left over.
     *
     * <p>A narration too long for its column wraps onto the line above, so a
     * line that fills the description column and carries no date of its own is
     * joined to the row beneath it.
     */
    private static List<List<String>> readByToken(List<String> lines, int headerLine,
            List<HeaderField> header) {

        int columns = header.size();
        int description = descriptionColumn(header);
        int trailing = columns - description - 1;

        int columnStart = header.get(description).start();
        int columnEnd = description + 1 < columns
                ? header.get(description + 1).start()
                : Integer.MAX_VALUE;
        int columnWidth = columnEnd == Integer.MAX_VALUE ? 0 : columnEnd - columnStart;

        List<List<String>> rows = new ArrayList<>();
        String wrapped = null;

        for (int i = headerLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);

            if (STARTS_WITH_DATE.matcher(line).matches()) {
                String[] tokens = line.strip().split("\\s+");
                if (tokens.length < columns) {
                    // Not enough words to fill the columns; something other than
                    // a transaction happens to start with a date.
                    wrapped = null;
                    continue;
                }

                List<String> row = new ArrayList<>(columns);
                for (int c = 0; c < description; c++) {
                    row.add(tokens[c]);
                }

                StringBuilder middle = new StringBuilder();
                if (wrapped != null) {
                    middle.append(wrapped);
                }
                for (int t = description; t < tokens.length - trailing; t++) {
                    if (middle.length() > 0) {
                        middle.append(' ');
                    }
                    middle.append(tokens[t]);
                }
                row.add(middle.toString());

                for (int t = tokens.length - trailing; t < tokens.length; t++) {
                    row.add(tokens[t]);
                }

                rows.add(row);
                wrapped = null;
            } else {
                wrapped = wrappedNarration(line, columnStart, columnEnd, columnWidth);
            }
        }
        return rows;
    }

    /**
     * The line's text when it looks like narration that wrapped, otherwise null.
     *
     * <p>Two things have to hold: it begins inside the description column rather
     * than out in the page furniture, and it is full enough to be text that ran
     * out of room. Only the nearest such line is kept, so a leftover from the
     * row above is displaced by the row below's own wrapped line.
     */
    private static String wrappedNarration(String line, int columnStart, int columnEnd,
            int columnWidth) {

        String text = line.strip();
        if (text.isEmpty() || columnWidth <= 0) {
            return null;
        }

        int indent = line.length() - line.stripLeading().length();
        boolean inColumn = indent >= columnStart - COLUMN_INDENT_TOLERANCE && indent < columnEnd;
        boolean filled = text.length() >= columnWidth * WRAPPED_LINE_FILL;

        return inColumn && filled ? text : null;
    }

    /**
     * Which column holds the description, according to the importer's own alias
     * table rather than a second opinion kept here.
     */
    private static int descriptionColumn(List<HeaderField> header) {
        List<String> names = header.stream().map(HeaderField::name).toList();
        int index = CsvColumns.fromHeader(names, List.of()).indexOf(CsvColumns.Field.DESCRIPTION);
        // Failing that, the column after the date: it is where every statement
        // puts it, and there is nothing better to guess.
        return index >= 0 ? index : Math.min(1, header.size() - 1);
    }

    // ---------------------------------------------------------------- header

    /**
     * The first line that names at least two columns the importer understands.
     *
     * <p>Reuses the importer's own alias table rather than keeping a second
     * list: a bank that calls it "Narration" should be recognised here for
     * exactly the reason it is recognised there.
     */
    private static int findHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            List<String> fields = parseHeader(lines.get(i)).stream()
                    .map(HeaderField::name)
                    .toList();
            if (fields.size() >= 3 && CsvColumns.looksLikeHeader(fields)) {
                return i;
            }
        }
        return -1;
    }

    /** Splits a header line into its names, keeping where each one starts. */
    private static List<HeaderField> parseHeader(String line) {
        List<HeaderField> fields = new ArrayList<>();
        Matcher matcher = HEADER_FIELD.matcher(line);
        while (matcher.find()) {
            fields.add(new HeaderField(matcher.start(), matcher.group().strip()));
        }
        return fields;
    }
}
