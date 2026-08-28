package com.lokesh_codes.expense_tracker_backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lokesh_codes.expense_tracker_backend.DTO.DateOrder;

/**
 * Reads the date layouts statements arrive in, in the order the user says to try
 * them.
 *
 * <p>The layouts themselves were never the hard part. The hard part is that
 * {@code 03/04/2026} is a valid date under two of them and means different
 * months, so the first layout that parses is not necessarily the right answer.
 */
final class CsvDates {

    /**
     * A two-digit year is read as 20xx. A statement is a record of something
     * that already happened, and no bank exporting {@code 14/08/26} means 1926.
     */
    private static final int TWO_DIGIT_YEAR_BASE = 2000;

    /** Slash or dash separated, two numbers then a year. Both readings are possible. */
    private static final Pattern NUMERIC_DATE =
            Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2}|\\d{4})$");

    private static final List<DateTimeFormatter> DAY_FIRST = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            twoDigitYear("dd/MM/"),
            twoDigitYear("dd-MM-"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));

    private static final List<DateTimeFormatter> MONTH_FIRST = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            twoDigitYear("MM/dd/"),
            twoDigitYear("MM-dd-"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH));

    private CsvDates() {
    }

    private static DateTimeFormatter twoDigitYear(String prefix) {
        return new DateTimeFormatterBuilder()
                .appendPattern(prefix)
                .appendValueReduced(ChronoField.YEAR, 2, 2, TWO_DIGIT_YEAR_BASE)
                .toFormatter();
    }

    static LocalDate parse(String raw, DateOrder order) {
        List<DateTimeFormatter> formats = order == DateOrder.MONTH_FIRST ? MONTH_FIRST : DAY_FIRST;

        for (DateTimeFormatter format : formats) {
            try {
                return LocalDate.parse(raw.trim(), format);
            } catch (DateTimeParseException ignored) {
                // Try the next known layout.
            }
        }
        throw new IllegalArgumentException("\"" + raw + "\" is not a date we recognise");
    }

    /**
     * What a date proves about the file's own ordering, regardless of what was
     * chosen.
     *
     * <p>{@code 14/08} can only be day-first, because there is no fourteenth
     * month. {@code 08/14} can only be month-first. Anything where both numbers
     * are twelve or under proves nothing. Collecting this lets the importer say
     * so when the file disagrees with the setting, instead of filing a year of
     * spending into the wrong months in silence.
     *
     * @return the order the value proves, or null when it is ambiguous
     */
    static DateOrder evidenceFrom(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = NUMERIC_DATE.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }

        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));

        if (first > 12 && second <= 12) {
            return DateOrder.DAY_FIRST;
        }
        if (second > 12 && first <= 12) {
            return DateOrder.MONTH_FIRST;
        }
        return null;
    }
}
