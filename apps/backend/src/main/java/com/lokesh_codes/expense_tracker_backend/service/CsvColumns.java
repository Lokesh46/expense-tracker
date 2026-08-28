package com.lokesh_codes.expense_tracker_backend.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which column holds what, by reading the header row.
 *
 * <p>The importer used to be purely positional, expecting
 * {@code Date,Description,Category,Amount,...}. Almost no bank exports that
 * shape: HSBC sends three columns and no header, Lloyds splits money into
 * separate debit and credit columns, Monzo sends sixteen columns with a
 * transaction id first, and an Indian statement calls the description
 * "Narration". Every one of those imported nothing at all.
 *
 * <p>So the header is read and matched against the names banks actually use. A
 * file with no recognisable header falls back to the old positional reading, so
 * files written for the previous behaviour still work.
 */
final class CsvColumns {

    /** What the importer needs to find, whatever the file calls it. */
    enum Field {
        DATE,
        DESCRIPTION,
        CATEGORY,
        AMOUNT,
        /** Money out, when a file splits the two directions into separate columns. */
        DEBIT,
        /** Money in, likewise. */
        CREDIT,
        CURRENCY,
        PAYMENT_METHOD,
        COMMENTS,
        /** Expense or income, when the file says so outright. */
        DIRECTION
    }

    /**
     * Header names seen in real exports, lowercased and stripped of punctuation.
     *
     * <p>Order matters within a field: the first alias that matches a column
     * wins, so the more specific names come first. Order matters between fields
     * too — a column is claimed by the first field that wants it.
     */
    private static final Map<Field, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        // Plain "Date" ranks high on purpose. A statement that carries both it
        // and a "Value Dt" means the first: the value date is when the bank
        // settled, not when the money was spent, and they differ by days.
        ALIASES.put(Field.DATE, List.of(
                "transaction date", "txn date", "trans date", "date", "booking date",
                "posting date", "post date", "posted date", "value date", "value dt"));
        ALIASES.put(Field.DEBIT, List.of(
                "debit amount", "withdrawal amt", "withdrawal amount", "withdrawal",
                "paid out", "money out", "debit", "dr"));
        ALIASES.put(Field.CREDIT, List.of(
                "credit amount", "deposit amt", "deposit amount", "deposit",
                "paid in", "money in", "credit", "cr"));
        // "Local amount" is last. On a Monzo export it is the amount in the
        // merchant's currency and pairs with "Local currency", while "Amount"
        // pairs with "Currency" — reading the first against the second turns a
        // foreign purchase into the wrong figure under the wrong label.
        ALIASES.put(Field.AMOUNT, List.of(
                "transaction amount", "amount gbp", "amount usd", "amount eur", "amount inr",
                "amount", "value", "local amount"));
        ALIASES.put(Field.DESCRIPTION, List.of(
                "transaction description", "description", "narration", "particulars",
                "counter party", "merchant", "payee", "name", "details", "reference"));
        ALIASES.put(Field.CATEGORY, List.of(
                "spending category", "subcategory", "category"));
        ALIASES.put(Field.CURRENCY, List.of("currency", "local currency"));
        ALIASES.put(Field.PAYMENT_METHOD, List.of("payment method", "method"));
        ALIASES.put(Field.COMMENTS, List.of(
                "notes and tags", "comments", "notes", "memo", "note"));
        ALIASES.put(Field.DIRECTION, List.of("direction", "debit/credit", "dr/cr", "in/out"));
    }

    /**
     * Names that mean one thing in our own export and another in a bank's.
     *
     * <p>Our exported {@code Type} column holds Expense or Income. Monzo's holds
     * "Card payment", Chase's holds "Sale", Lloyds' holds "DEB". The name alone
     * cannot separate them, so the column's own values decide — see
     * {@link #claimAmbiguousTypeColumn}.
     */
    private static final List<String> AMBIGUOUS_TYPE_NAMES = List.of("type", "transaction type");

    /**
     * How many data rows are sampled when a column's name does not settle what
     * it holds. Enough to see past a blank first row; few enough to stay cheap.
     */
    private static final int SNIFF_ROWS = 20;

    /** Matches a currency a bank puts in brackets, as in "Amount (GBP)". */
    private static final Pattern CURRENCY_IN_HEADER =
            Pattern.compile("\\(\\s*([A-Za-z]{3})\\s*\\)");

    /** The positional layout, used when a file has no header we recognise. */
    private static final List<Field> POSITIONAL = List.of(
            Field.DATE, Field.DESCRIPTION, Field.CATEGORY, Field.AMOUNT,
            Field.CURRENCY, Field.PAYMENT_METHOD, Field.COMMENTS, Field.DIRECTION);

    /**
     * A headerless file of exactly three columns, which is what a bank sends
     * when it sends the minimum: when, what, how much.
     */
    private static final List<Field> POSITIONAL_MINIMAL = List.of(
            Field.DATE, Field.DESCRIPTION, Field.AMOUNT);

    private final Map<Field, Integer> indexes;
    private final Map<Field, String> matchedNames;
    private final boolean fromHeader;

    private CsvColumns(Map<Field, Integer> indexes, Map<Field, String> matchedNames,
            boolean fromHeader) {
        this.indexes = indexes;
        this.matchedNames = matchedNames;
        this.fromHeader = fromHeader;
    }

    // ------------------------------------------------------------- detection

    /**
     * Whether a record is a header rather than data.
     *
     * <p>Two recognised names is the threshold. One is too easy to hit by
     * accident — a description of "Reference" would do it — and a header row
     * that only names one column it can use is not worth trusting over the
     * positional reading.
     */
    static boolean looksLikeHeader(List<String> fields) {
        int recognised = 0;
        for (String field : fields) {
            if (fieldFor(normalise(field)) != null || isAmbiguousTypeName(normalise(field))) {
                recognised++;
            }
        }
        return recognised >= 2;
    }

    /**
     * Reads a header row into a mapping.
     *
     * <p>When two columns could fill the same field, the better <em>name</em>
     * wins, not the earlier column. Monzo sends both "Name" ("Tesco") and
     * "Description" ("TESCO STORES 3421"), with Name first; taking the first
     * column meant importing the tidied-up merchant instead of the text the
     * filing rules are written against. Alias order is the ranking, so the more
     * specific name wins wherever it sits in the row.
     */
    static CsvColumns fromHeader(List<String> header, List<List<String>> dataRows) {
        Map<Field, Integer> indexes = new EnumMap<>(Field.class);
        Map<Field, String> names = new EnumMap<>(Field.class);
        Map<Field, Integer> bestRank = new EnumMap<>(Field.class);

        for (int i = 0; i < header.size(); i++) {
            String name = normalise(header.get(i));
            if (name.isEmpty()) {
                continue;
            }

            Field field = fieldFor(name);
            int rank;
            if (field != null) {
                rank = ALIASES.get(field).indexOf(name);
            } else if (isAmbiguousTypeName(name)) {
                field = claimAmbiguousTypeColumn(dataRows, i);
                // The weakest claim there is: a column named "Type" only holds
                // this field because nothing better was found for it.
                rank = Integer.MAX_VALUE;
            } else {
                continue;
            }

            Integer current = bestRank.get(field);
            if (current == null || rank < current) {
                bestRank.put(field, rank);
                indexes.put(field, i);
                names.put(field, header.get(i).trim());
            }
        }

        return new CsvColumns(indexes, names, true);
    }

    /**
     * The layout to assume when there is no header.
     *
     * <p>Three columns is read as date, description, amount — the minimum a
     * statement can carry, and unambiguous once the third column is confirmed to
     * be a number. Anything wider keeps the original positional layout.
     */
    static CsvColumns positional(List<String> firstDataRow) {
        List<Field> layout = firstDataRow.size() == 3 && looksNumeric(firstDataRow.get(2))
                ? POSITIONAL_MINIMAL
                : POSITIONAL;

        Map<Field, Integer> indexes = new EnumMap<>(Field.class);
        for (int i = 0; i < layout.size(); i++) {
            indexes.put(layout.get(i), i);
        }
        return new CsvColumns(indexes, new EnumMap<>(Field.class), false);
    }

    /**
     * Decides whether an ambiguously named column holds a direction or a payment
     * method, by looking at what is actually in it.
     *
     * <p>A sample of rows rather than the first one alone. A file whose opening
     * row happens to be blank, or to carry a typo, would otherwise decide the
     * meaning of the whole column — and getting this wrong is not a small
     * mistake: a direction column read as a payment method files every credit in
     * the file as an expense.
     *
     * <p>One recognised value is enough to claim it. Nothing recognised anywhere
     * in the sample means it is a payment method, which is what Monzo's "Card
     * payment" and Chase's "Sale" are.
     */
    private static Field claimAmbiguousTypeColumn(List<List<String>> dataRows, int index) {
        if (dataRows == null) {
            return Field.PAYMENT_METHOD;
        }
        int limit = Math.min(dataRows.size(), SNIFF_ROWS);
        for (int row = 0; row < limit; row++) {
            List<String> fields = dataRows.get(row);
            if (index >= fields.size()) {
                continue;
            }
            if (TransactionDirection.recognises(normalise(fields.get(index)))) {
                return Field.DIRECTION;
            }
        }
        return Field.PAYMENT_METHOD;
    }

    private static boolean isAmbiguousTypeName(String name) {
        return AMBIGUOUS_TYPE_NAMES.contains(name);
    }

    private static Field fieldFor(String name) {
        for (Map.Entry<Field, List<String>> entry : ALIASES.entrySet()) {
            if (entry.getValue().contains(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Lowercases and drops the punctuation banks sprinkle through header names,
     * so {@code "Withdrawal Amt."} and {@code "Amount (GBP)"} match.
     */
    private static String normalise(String name) {
        // Slashes are left alone: "dr/cr" and "in/out" are meaningful as
        // written, and flattening them would merge two distinct header names.
        return name.toLowerCase(Locale.ROOT)
                .replace('.', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('#', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean looksNumeric(String value) {
        String cleaned = value.replaceAll("[^0-9.()\\-]", "").trim();
        return !cleaned.isEmpty() && cleaned.matches("[-(]?\\d[\\d.,]*\\)?");
    }

    // ----------------------------------------------------------------- access

    /** The field's column index, or -1 when the file does not carry it. */
    int indexOf(Field field) {
        return indexes.getOrDefault(field, -1);
    }

    boolean has(Field field) {
        return indexes.containsKey(field);
    }

    boolean isFromHeader() {
        return fromHeader;
    }

    /**
     * A currency named inside the amount column's own header.
     *
     * <p>Several UK banks label the column {@code "Amount (GBP)"} and then send
     * no currency column at all. Reading it here is the difference between a
     * statement importing as pounds and importing as the default, which is
     * dollars.
     *
     * @return the three-letter code, or null when the header does not name one
     */
    String currencyHint() {
        for (Field field : List.of(Field.AMOUNT, Field.DEBIT, Field.CREDIT)) {
            String name = matchedNames.get(field);
            if (name == null) {
                continue;
            }
            Matcher matcher = CURRENCY_IN_HEADER.matcher(name);
            if (matcher.find()) {
                return matcher.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** True when money is split across separate debit and credit columns. */
    boolean hasSplitAmounts() {
        return !has(Field.AMOUNT) && (has(Field.DEBIT) || has(Field.CREDIT));
    }

    /** Whether enough was recognised to read a row at all. */
    boolean isUsable() {
        return has(Field.DATE) && has(Field.DESCRIPTION)
                && (has(Field.AMOUNT) || has(Field.DEBIT) || has(Field.CREDIT));
    }

    /**
     * A plain-language summary of what was matched, shown back to the user.
     *
     * <p>A mapping that silently guessed wrong is worse than one that failed, so
     * the guess is always reported rather than left to be inferred from the
     * results.
     */
    String summary() {
        if (!fromHeader) {
            return "No header recognised; columns read by position.";
        }
        List<String> parts = new ArrayList<>();
        matchedNames.forEach((field, name) -> parts.add(label(field) + " ← \"" + name + "\""));
        return parts.isEmpty()
                ? "No columns recognised."
                : "Columns recognised: " + String.join(", ", parts) + ".";
    }

    private static String label(Field field) {
        return switch (field) {
            case DATE -> "Date";
            case DESCRIPTION -> "Description";
            case CATEGORY -> "Category";
            case AMOUNT -> "Amount";
            case DEBIT -> "Money out";
            case CREDIT -> "Money in";
            case CURRENCY -> "Currency";
            case PAYMENT_METHOD -> "Payment method";
            case COMMENTS -> "Comments";
            case DIRECTION -> "Expense or income";
        };
    }
}
