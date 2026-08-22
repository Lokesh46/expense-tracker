package com.lokesh_codes.expense_tracker_backend.service;

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

    private CsvSupport() {
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
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
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
