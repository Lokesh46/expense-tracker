package com.lokesh_codes.expense_tracker_backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reading and writing.
 *
 * <p>Hand-rolled rather than pulled in as a dependency: the format needed here
 * is small, but it still has to handle quoted fields containing commas,
 * newlines and escaped quotes, which naive {@code split(",")} does not.
 */
final class CsvSupport {

    /**
     * Characters a spreadsheet reads as the start of a formula rather than as
     * text. A description is attacker-controlled — it arrives from an import, or
     * from a merchant name — so an export that reproduces one faithfully hands
     * whoever opens the file an executable cell.
     */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    /** The marker a neutralised value carries, and that import strips back off. */
    static final char FORMULA_GUARD = '\'';

    /**
     * The byte-order mark Excel writes at the start of a UTF-8 CSV.
     *
     * <p>It arrives as the first character of the first line and is invisible,
     * which is what made it expensive: it defeated the header check, so every
     * file saved from Excel reported a spurious error on line 1 and lost its
     * first row.
     */
    private static final char BOM = '\uFEFF';

    /**
     * How many physical lines one record may span before we stop joining them.
     *
     * <p>A single unbalanced quote in a file would otherwise swallow every
     * remaining line into one field, turning a small mistake into a file that
     * imports as one nonsensical row.
     */
    private static final int MAX_LINES_PER_RECORD = 10;

    private CsvSupport() {
    }

    /** One logical CSV record, and how many physical lines it occupied. */
    record CsvRecord(String text, int lines) {
    }

    /**
     * Reads one record, joining continuation lines when a quoted field contains
     * a newline.
     *
     * <p>Reading line by line and parsing each in isolation is wrong for real
     * exports: RFC 4180 allows a newline inside a quoted field, and a bank that
     * puts an address in the description produces one. Splitting there leaves
     * both halves unparseable.
     *
     * @return the record, or null at end of input
     */
    static CsvRecord nextRecord(BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null) {
            return null;
        }

        StringBuilder record = new StringBuilder(stripBom(first));
        int lines = 1;

        while (hasUnclosedQuote(record) && lines < MAX_LINES_PER_RECORD) {
            String continuation = reader.readLine();
            if (continuation == null) {
                break;
            }
            record.append('\n').append(continuation);
            lines++;
        }

        return new CsvRecord(record.toString(), lines);
    }

    /** Removes the byte-order mark, if the line begins with one. */
    static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
    }

    /**
     * Whether a quoted field is still open at the end of the text.
     *
     * <p>Uses the same rule as {@link #parseLine}: a doubled quote inside a
     * quoted field is a literal quote and does not close it.
     */
    private static boolean hasUnclosedQuote(CharSequence text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '"') {
                continue;
            }
            if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                i++;
                continue;
            }
            inQuotes = !inQuotes;
        }
        return inQuotes;
    }

    /** Splits a single CSV record into fields. */
    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    /** Quotes a value only when it would otherwise be ambiguous. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }

        String safe = neutraliseFormula(value);

        boolean needsQuotes = safe.contains(",") || safe.contains("\"")
                || safe.contains("\n") || safe.contains("\r");
        if (!needsQuotes) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    /**
     * Prefixes a value a spreadsheet would evaluate, so it is displayed as
     * written instead.
     *
     * <p>Numbers are left alone. An amount of {@code -45.00} starts with a
     * formula character but is only ever read as a number, and guarding it would
     * corrupt every negative figure in the file to buy nothing.
     */
    static String neutraliseFormula(String value) {
        if (value.isEmpty() || FORMULA_STARTERS.indexOf(value.charAt(0)) < 0) {
            return value;
        }
        if (isNumeric(value)) {
            return value;
        }
        return FORMULA_GUARD + value;
    }

    /**
     * Removes a guard added by {@link #neutraliseFormula}, so exporting a file
     * and importing it again returns the original text.
     */
    static String stripFormulaGuard(String value) {
        if (value.length() > 1 && value.charAt(0) == FORMULA_GUARD
                && FORMULA_STARTERS.indexOf(value.charAt(1)) >= 0) {
            return value.substring(1);
        }
        return value;
    }

    private static boolean isNumeric(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String row(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values[i] == null ? "" : String.valueOf(values[i])));
        }
        return sb.append('\n').toString();
    }
}
