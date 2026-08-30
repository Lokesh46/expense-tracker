package com.lokesh_codes.expense_tracker_backend.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.lokesh_codes.expense_tracker_backend.DTO.DateOrder;
import com.lokesh_codes.expense_tracker_backend.DTO.ImportResultDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.CategoryRule;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.entity.TransactionType;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;
import com.lokesh_codes.expense_tracker_backend.service.CsvColumns.Field;

import jakarta.persistence.EntityManager;

/** CSV import and export for transactions. */
@Service
public class TransactionCsvService {

    /**
     * Type is last on purpose. Appending keeps every file exported before it
     * existed importable, and every hand-made file people already have; a column
     * inserted in the middle would silently shift four fields.
     */
    static final String HEADER =
            "Date,Description,Category,Amount,Currency,Payment Method,Comments,Type";

    /** Guards against a huge upload being held entirely in memory. */
    private static final int MAX_ROWS = 10_000;

    /** How many rows are written before the persistence context is cleared. */
    private static final int SAVE_BATCH = 500;

    private static final String DEFAULT_COLOR = "#6366f1";

    /** Where a row goes when no rule fires and the file names nothing we know. */
    private static final String UNCATEGORISED = "Uncategorised";

    /**
     * How far into the file to look for evidence that it is not text at all.
     * A statement's first rows are representative; reading further to reject a
     * file we have already decided about buys nothing.
     */
    private static final int SNIFF_BYTES = 8_192;

    /** Mirrors the limits TransactionDTO applies to a hand-recorded expense. */
    private static final int MAX_DESCRIPTION = 200;
    private static final int MAX_COMMENTS = 500;
    private static final int MAX_CATEGORY_NAME = 60;

    /** Raw values are echoed back in error messages; a whole field is not needed to identify the row. */
    private static final int MAX_ECHOED_VALUE = 60;

    /** Beyond this, one error per row would bury the one that explains the rest. */
    private static final int MAX_REPORTED_ERRORS = 20;

    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CurrentUserService currentUser;
    private final ActivityLogService activity;
    private final RateLimiter rateLimiter;
    private final TransactionIndexer indexer;
    private final CategoryRuleService ruleService;
    private final PdfStatementReader pdfReader;
    private final EntityManager entityManager;
    private final int maxImportsPerHour;
    private final int maxExportsPerHour;

    public TransactionCsvService(TransactionService transactionService,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            CurrentUserService currentUser,
            ActivityLogService activity,
            RateLimiter rateLimiter,
            TransactionIndexer indexer,
            CategoryRuleService ruleService,
            PdfStatementReader pdfReader,
            EntityManager entityManager,
            @Value("${app.csv.max-imports-per-hour:5}") int maxImportsPerHour,
            @Value("${app.csv.max-exports-per-hour:10}") int maxExportsPerHour) {
        this.transactionService = transactionService;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.currentUser = currentUser;
        this.activity = activity;
        this.rateLimiter = rateLimiter;
        this.indexer = indexer;
        this.ruleService = ruleService;
        this.pdfReader = pdfReader;
        this.entityManager = entityManager;
        this.maxImportsPerHour = maxImportsPerHour;
        this.maxExportsPerHour = maxExportsPerHour;
    }

    /**
     * Exports every transaction matching the filter, newest first.
     *
     * <p>Deliberately not {@code @Transactional(readOnly = true)}, though it is
     * overwhelmingly a read. It also writes an audit row at the end, and
     * PostgreSQL enforces a read-only transaction where H2 treats it as a hint:
     * marked read-only, this method worked in every test and returned a 500 in
     * production with "cannot execute INSERT in a read-only transaction".
     *
     * <p>Nothing is lost by leaving it off. The query runs inside
     * {@code TransactionService.findAll}, which is read-only itself, and the
     * audit row gets its own transaction.
     */
    public String export(TransactionFilter filter) {
        User user = currentUser.require();
        rateLimiter.require("export:" + user.getId(), maxExportsPerHour,
                "You have exported several times in the last hour. Try again shortly.");

        List<TransactionDTO> transactions = new ArrayList<>(transactionService.findAll(filter));
        transactions.sort((a, b) -> b.getDate().compareTo(a.getDate()));

        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (TransactionDTO t : transactions) {
            csv.append(CsvSupport.row(
                    t.getDate(),
                    t.getDescription(),
                    t.getCategoryName(),
                    t.getAmount().toPlainString(),
                    t.getCurrency(),
                    t.getPaymentMethod(),
                    t.getComments(),
                    t.getType() == null ? TransactionType.EXPENSE.name() : t.getType().name()));
        }

        // A count, never the rows themselves. Export is the path a whole ledger
        // leaves by, so it is worth a record; what left is the account holder's
        // business and does not belong in a table an administrator can read.
        activity.record(ActivityAction.TRANSACTIONS_EXPORTED, user.getUsername(),
                transactions.size() + " transactions");

        return csv.toString();
    }

    /**
     * Imports transactions from a CSV file.
     *
     * <p>Read in two passes. The first collects the rows and works out what the
     * file is: which column holds what, whether money is split across debit and
     * credit columns, and whether the sign of the amount carries meaning. Only
     * then are transactions built. A single pass cannot do this — the meaning of
     * a minus sign in row one depends on whether row nine hundred also has one —
     * and guessing per row is how a file half-imports as the wrong thing.
     *
     * <p>Each row is still validated on its own and a bad row is reported rather
     * than aborting the file, so one malformed line in a bank export does not
     * discard everything else.
     *
     * <p>The upload is never written anywhere. It is held in memory for the
     * length of this call and discarded — see the multipart threshold in
     * {@code application.properties}, which is what keeps the servlet container
     * from spilling it to a temp file before this method is reached.
     */
    @Transactional
    public ImportResultDTO importCsv(MultipartFile file, DateOrder dateOrder,
            String defaultCurrency, String pdfPassword) throws IOException {
        User user = currentUser.require();
        rateLimiter.require("import:" + user.getId(), maxImportsPerHour,
                "You have imported several files in the last hour. Try again shortly.");

        byte[] uploaded = file.getBytes();

        DateOrder order = dateOrder == null ? DateOrder.DAY_FIRST : dateOrder;
        String fallbackCurrency = normaliseCurrency(defaultCurrency);
        List<String> errors = new ArrayList<>();

        // A PDF is turned into CSV and then imported as one. Everything past
        // this point -- column names, dates, rules, duplicates -- is the same
        // code for both, which is the point: a statement should not behave
        // differently for having arrived as a PDF.
        byte[] content;
        if (PdfStatementReader.looksLikePdf(uploaded)) {
            content = fromPdf(uploaded, pdfPassword, errors);
            if (content.length == 0) {
                return new ImportResultDTO(0, 0, 0, errors,
                        "No transaction table could be read from that PDF.");
            }
        } else {
            rejectIfNotText(uploaded);
            content = uploaded;
        }

        List<RawRow> rows = readRows(content, errors);
        if (rows.isEmpty()) {
            return new ImportResultDTO(0, 0, 0, errors, "Nothing to read in that file.");
        }

        CsvColumns columns = describeColumns(rows);
        if (!columns.isUsable()) {
            errors.add("Could not find a date, a description and an amount. " + columns.summary()
                    + " Rename those columns, or re-save the file as "
                    + "Date,Description,Category,Amount.");
            return new ImportResultDTO(0, 0, 0, errors, columns.summary());
        }

        warnIfDatesContradictTheChosenOrder(rows, columns, order, errors);

        ImportContext context = buildContext(user, columns, rows, order, fallbackCurrency);
        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (RawRow row : rows) {
            try {
                toSave.add(parseRow(row.fields(), user, context));
            } catch (IllegalArgumentException e) {
                skipped++;
                if (errors.size() < MAX_REPORTED_ERRORS) {
                    errors.add("Line " + row.line() + ": " + e.getMessage());
                }
            }
        }

        // Reported rather than silently filed away: a name the user expected to
        // work and that did nothing is the one thing they cannot see from the
        // result otherwise.
        if (!context.unrecognisedCategories().isEmpty()) {
            errors.add(unrecognisedMessage(context.unrecognisedCategories()));
        }

        int flagged = flagDuplicates(user, toSave);
        saveInBatches(toSave);

        activity.record(ActivityAction.TRANSACTIONS_IMPORTED, user.getUsername(),
                toSave.size() + " imported, " + skipped + " skipped, " + flagged + " flagged");

        return new ImportResultDTO(toSave.size(), skipped, flagged, errors, columns.summary());
    }

    // -------------------------------------------------------------- reading

    /** One record of the file, and the line its first field started on. */
    private record RawRow(int line, List<String> fields) {
    }

    private List<RawRow> readRows(byte[] content, List<String> errors) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {

            CsvSupport.CsvRecord record;
            while ((record = CsvSupport.nextRecord(reader)) != null) {
                // The first line of the record is the one worth naming in an
                // error; a record that spans several still points at its start.
                int startLine = lineNumber + 1;
                lineNumber += record.lines();

                if (record.text().isBlank()) {
                    continue;
                }

                // Counted against records read, not records kept. Counting the
                // ones that parsed meant a file of half a million malformed
                // lines never reached the cap and was read from end to end.
                if (rows.size() >= MAX_ROWS) {
                    errors.add("Stopped at " + MAX_ROWS
                            + " rows. Split the file and import the rest separately.");
                    break;
                }
                rows.add(new RawRow(startLine, CsvSupport.parseLine(record.text())));
            }
        }
        return rows;
    }

    /**
     * Works out the file's shape, consuming the header row if there is one.
     *
     * <p>Removes the header from {@code rows} so the caller is left with data
     * only.
     */
    private CsvColumns describeColumns(List<RawRow> rows) {
        List<String> first = rows.get(0).fields();

        if (CsvColumns.looksLikeHeader(first)) {
            rows.remove(0);
            // The data itself settles what an ambiguously named column holds, so
            // the rows have to be available before the mapping is fixed.
            List<List<String>> dataRows = rows.stream().map(RawRow::fields).toList();
            return CsvColumns.fromHeader(first, dataRows);
        }
        return CsvColumns.positional(first);
    }

    // ------------------------------------------------------------- analysis

    /**
     * Tells the user when the file's own dates disagree with the ordering they
     * chose.
     *
     * <p>A date of {@code 14/08} can only be day-first; there is no fourteenth
     * month. If the file proves an order and the import was told the other one,
     * every ambiguous date in it has just been read wrongly — silently, because
     * both readings parse. Saying so is the only way the user finds out.
     */
    private void warnIfDatesContradictTheChosenOrder(List<RawRow> rows, CsvColumns columns,
            DateOrder chosen, List<String> errors) {

        int dateIndex = columns.indexOf(Field.DATE);
        for (RawRow row : rows) {
            DateOrder proven = CsvDates.evidenceFrom(fieldAt(row.fields(), dateIndex));
            if (proven != null && proven != chosen) {
                errors.add("The dates in this file look like " + proven.label()
                        + ", but the import was set to " + chosen.label()
                        + ". Delete these transactions and import again with the other setting.");
                return;
            }
        }
    }

    /**
     * Whether a minus sign in the amount column means anything in this file.
     *
     * <p>Some banks export debits negative and credits positive; others export
     * everything positive and rely on a separate column. Reading the sign in the
     * second kind of file would mark every row income. So the sign is trusted
     * only when the file itself shows both — at least one negative amount —
     * which is exactly the case where it carries information.
     */
    private boolean signCarriesMeaning(List<RawRow> rows, CsvColumns columns) {
        if (columns.has(Field.DIRECTION) || columns.hasSplitAmounts()) {
            return false; // The file says outright; the sign is not needed.
        }
        int amountIndex = columns.indexOf(Field.AMOUNT);
        for (RawRow row : rows) {
            BigDecimal amount = tryParseAmount(fieldAt(row.fields(), amountIndex));
            if (amount != null && amount.signum() < 0) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- parsing

    private ImportContext buildContext(User user, CsvColumns columns, List<RawRow> rows,
            DateOrder order, String fallbackCurrency) {
        // Both loaded once, before the row loop. Evaluating rules or looking up a
        // category per row would turn a ten-thousand-row file into twenty
        // thousand queries.
        Map<String, Category> categoriesByName = new HashMap<>();
        categoryRepository.findByUser_IdOrderByNameAsc(user.getId())
                .forEach(category -> categoriesByName.put(lower(category.getName()), category));

        return new ImportContext(
                categoriesByName,
                ruleService.activeRulesFor(user.getId()),
                new LinkedHashSet<>(),
                columns,
                order,
                signCarriesMeaning(rows, columns),
                fallbackCurrency);
    }

    private Transaction parseRow(List<String> fields, User user, ImportContext context) {
        CsvColumns columns = context.columns();

        LocalDate date = CsvDates.parse(
                required(fields, columns.indexOf(Field.DATE), "date"), context.dateOrder());

        // A file we exported carries a guard on any value a spreadsheet would
        // otherwise evaluate. Removing it here is what makes export then import
        // return the text the user actually wrote.
        String description = CsvSupport.stripFormulaGuard(
                required(fields, columns.indexOf(Field.DESCRIPTION), "description"));
        // Import does not go through the DTO, so nothing else applies the limits
        // the rest of the application does. Without this a single overlong field
        // fails at the insert, which rolls back the whole file -- one bad row
        // taking everything with it is exactly what this method exists to avoid.
        requireAtMost(description, MAX_DESCRIPTION, "description");

        String categoryName = CsvSupport.stripFormulaGuard(
                fieldAt(fields, columns.indexOf(Field.CATEGORY)));
        requireAtMost(categoryName, MAX_CATEGORY_NAME, "category name");

        Money money = readMoney(fields, context);
        Category category = resolveCategory(description, categoryName, user, context);

        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setCategory(category);
        transaction.setDate(date);
        transaction.setDescription(description);
        transaction.setAmount(money.amount());
        transaction.setType(money.type());
        transaction.setCurrency(readCurrency(fields, context));
        transaction.setPaymentMethod(orDefault(
                CsvSupport.stripFormulaGuard(fieldAt(fields, columns.indexOf(Field.PAYMENT_METHOD))),
                "Other"));

        String comments = CsvSupport.stripFormulaGuard(
                fieldAt(fields, columns.indexOf(Field.COMMENTS)));
        requireAtMost(comments, MAX_COMMENTS, "comments");
        transaction.setComments(comments);

        indexer.index(transaction);
        return transaction;
    }

    /** An amount and the direction it went. */
    private record Money(BigDecimal amount, TransactionType type) {
    }

    /**
     * Reads how much moved and which way, from whichever shape the file uses.
     *
     * <p>Three shapes, in order of how directly they say it: separate debit and
     * credit columns, a single amount with a direction column beside it, and a
     * single amount whose sign has to carry the meaning.
     */
    private Money readMoney(List<String> fields, ImportContext context) {
        CsvColumns columns = context.columns();

        if (columns.hasSplitAmounts()) {
            BigDecimal debit = tryParseAmount(fieldAt(fields, columns.indexOf(Field.DEBIT)));
            if (debit != null && debit.signum() != 0) {
                return new Money(scale(debit.abs()), TransactionType.EXPENSE);
            }
            BigDecimal credit = tryParseAmount(fieldAt(fields, columns.indexOf(Field.CREDIT)));
            if (credit != null && credit.signum() != 0) {
                return new Money(scale(credit.abs()), TransactionType.INCOME);
            }
            throw new IllegalArgumentException("neither a debit nor a credit amount was given");
        }

        String raw = required(fields, columns.indexOf(Field.AMOUNT), "amount");
        BigDecimal signed = tryParseAmount(raw);
        if (signed == null) {
            throw new IllegalArgumentException(quote(raw) + " is not an amount");
        }

        BigDecimal amount = scale(signed.abs());
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }

        if (columns.has(Field.DIRECTION)) {
            String direction = fieldAt(fields, columns.indexOf(Field.DIRECTION));
            if (!direction.isBlank()) {
                return new Money(amount, TransactionDirection.parse(direction));
            }
        }

        if (context.signCarriesMeaning()) {
            return new Money(amount,
                    signed.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME);
        }
        return new Money(amount, TransactionType.EXPENSE);
    }

    /**
     * The currency for a row.
     *
     * <p>A column wins; failing that, a currency named in the amount column's own
     * header — {@code "Amount (GBP)"} is how several UK banks say it — and
     * failing that the previous default.
     */
    private String readCurrency(List<String> fields, ImportContext context) {
        String fromColumn = fieldAt(fields, context.columns().indexOf(Field.CURRENCY));
        if (!fromColumn.isBlank()) {
            return fromColumn.toUpperCase(java.util.Locale.ROOT);
        }
        String hint = context.columns().currencyHint();
        return hint != null ? hint : context.fallbackCurrency();
    }

    /**
     * Tolerates currency symbols, thousands separators and parenthesised
     * negatives, and keeps the sign so the caller can decide what it means.
     *
     * @return the value, or null when it is not a number at all
     */
    private BigDecimal tryParseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9.()-]", "").trim();

        boolean parenthesised = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (parenthesised) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }

        try {
            BigDecimal value = new BigDecimal(cleaned);
            // Accountants write a negative in brackets; the minus is implied.
            return parenthesised ? value.abs().negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------- helpers

    /**
     * The currency to fall back on, kept to three letters.
     *
     * <p>Most statements name no currency at all — a UK bank has no reason to
     * repeat "GBP" on every row — so the fallback is what a whole import ends up
     * labelled with. Anything unusable becomes the previous default rather than
     * failing the upload.
     */
    private String normaliseCurrency(String raw) {
        if (raw == null) {
            return "USD";
        }
        String cleaned = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return cleaned.matches("[A-Z]{3}") ? cleaned : "USD";
    }

    /** The value at a column index, or "" when the file does not carry it. */
    private String fieldAt(List<String> fields, int index) {
        if (index < 0 || index >= fields.size()) {
            return "";
        }
        return fields.get(index);
    }

    private String required(List<String> fields, int index, String what) {
        String value = fieldAt(fields, index);
        if (value.isBlank()) {
            throw new IllegalArgumentException("no " + what + " in this row");
        }
        return value;
    }

    private String orDefault(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    /**
     * Writes the parsed rows a batch at a time.
     *
     * <p>One {@code saveAll} of ten thousand entities keeps every one of them
     * managed until the transaction commits, and Hibernate re-checks all of them
     * on each flush. Clearing between batches keeps that cost flat instead of
     * quadratic, which matters on an instance with 512 MB.
     */
    private void saveInBatches(List<Transaction> rows) {
        for (int start = 0; start < rows.size(); start += SAVE_BATCH) {
            int end = Math.min(start + SAVE_BATCH, rows.size());
            transactionRepository.saveAll(rows.subList(start, end));
            transactionRepository.flush();
            entityManager.clear();
        }
    }

    /**
     * Everything an import needs to read a row, gathered once.
     *
     * @param categoriesByName       the user's categories, keyed by lowercased name
     * @param rules                  active filing rules, in the order to try them
     * @param unrecognisedCategories names in the file that matched no category,
     *                               collected so the user can be told
     * @param columns                which column holds what
     * @param dateOrder              how to read an ambiguous slash-separated date
     * @param signCarriesMeaning     whether a minus sign marks money going out
     * @param fallbackCurrency       what to use when the file names none
     */
    private record ImportContext(Map<String, Category> categoriesByName,
            List<CategoryRule> rules,
            Set<String> unrecognisedCategories,
            CsvColumns columns,
            DateOrder dateOrder,
            boolean signCarriesMeaning,
            String fallbackCurrency) {
    }

    private String unrecognisedMessage(Set<String> names) {
        List<String> shown = names.stream().limit(5).toList();
        String list = String.join(", ", shown);
        String more = names.size() > shown.size()
                ? " and " + (names.size() - shown.size()) + " more"
                : "";
        return "Filed under \"" + UNCATEGORISED + "\" because no category or rule matched: "
                + list + more + ".";
    }

    /**
     * Marks rows that match a transaction already on file, or an earlier row in
     * the same file.
     *
     * <p>Everything is still imported. Dropping a row on suspicion loses data the
     * user cannot get back without finding the file again, and two identical
     * payments on one day is an ordinary thing that no rule can tell apart from a
     * statement imported twice. Flagging leaves the decision with the only party
     * who can make it.
     *
     * <p>Existing fingerprints are fetched for the span the file covers rather
     * than for the whole ledger: a statement covers a month, and the rest of the
     * account cannot contain a duplicate of a row dated inside it.
     */
    private int flagDuplicates(User user, List<Transaction> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        LocalDate from = rows.get(0).getDate();
        LocalDate to = from;
        for (Transaction row : rows) {
            if (row.getDate().isBefore(from)) {
                from = row.getDate();
            }
            if (row.getDate().isAfter(to)) {
                to = row.getDate();
            }
        }

        Set<String> seen = new HashSet<>(
                transactionRepository.findFingerprintsBetween(user.getId(), from, to));

        int flagged = 0;
        for (Transaction row : rows) {
            String fingerprint = row.getFingerprint();
            if (fingerprint == null) {
                continue;
            }
            // add() answers false when the fingerprint was already present,
            // which covers both cases at once: a match against the database, and
            // a repeat of a row earlier in this same file.
            if (!seen.add(fingerprint)) {
                row.setPossibleDuplicate(true);
                flagged++;
            }
        }
        return flagged;
    }

    /**
     * Reads a statement PDF and hands back the CSV it contains.
     *
     * <p>Returns an empty array when no table could be found, with the reason
     * added to {@code errors}: a PDF that extracted cleanly but held nothing the
     * importer recognises is a different problem from one that would not open,
     * and saying "0 imported" for both helps nobody.
     */
    private byte[] fromPdf(byte[] uploaded, String password, List<String> errors) {
        List<String> lines = pdfReader.readLines(uploaded, password);
        PdfStatementTable.Extracted table = PdfStatementTable.toCsv(lines);

        if (table.isEmpty()) {
            errors.add(table.headerLine() < 0
                    ? "No transaction table was found in that PDF. Its columns need to include "
                            + "a date, a description and an amount under recognisable headings."
                    : "A table was found in that PDF but none of its rows began with a date.");
            return new byte[0];
        }

        return table.csv().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rejects an upload that is not a text file.
     *
     * <p>The content type is not consulted: a browser sends
     * {@code application/vnd.ms-excel} for a {@code .csv} on Windows and
     * {@code application/octet-stream} on some Linux desktops, so trusting it
     * rejects real statements. What a file <em>is</em> settles the question, and
     * a NUL byte does not occur in UTF-8 text.
     */
    private void rejectIfNotText(byte[] content) {
        if (content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That file is empty.");
        }

        int limit = Math.min(content.length, SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That does not look like a CSV file. Export your statement as CSV and try again.");
            }
        }
    }

    /**
     * Decides which category a row is filed under.
     *
     * <p>In order: a filing rule the user wrote, then the file's own Category
     * column but only if that category already exists, then "Uncategorised".
     *
     * <p>The middle step used to create whatever the column said. That turned one
     * typo in a bank export into a permanent category, and there was no way to
     * tell a real new category from a misspelling of an old one. Names that are
     * not recognised are now reported back instead, so the user can create the
     * category, or write a rule, and import again.
     */
    private Category resolveCategory(String description, String categoryName, User user,
            ImportContext context) {

        for (CategoryRule rule : context.rules()) {
            if (rule.getMatchType().matches(description, rule.getPattern())) {
                return rule.getCategory();
            }
        }

        if (!categoryName.isBlank()) {
            Category named = context.categoriesByName().get(lower(categoryName));
            if (named != null) {
                return named;
            }
            context.unrecognisedCategories().add(categoryName);
        }

        return context.categoriesByName().computeIfAbsent(lower(UNCATEGORISED),
                key -> categoryRepository.save(new Category(user, UNCATEGORISED, DEFAULT_COLOR)));
    }

    private void requireAtMost(String value, int max, String field) {
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    field + " is " + value.length() + " characters; the limit is " + max);
        }
    }

    private String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /** Echoes a field back so the user can find the row, without repeating the whole of it. */
    private String quote(String value) {
        String shown = value.length() <= MAX_ECHOED_VALUE
                ? value
                : value.substring(0, MAX_ECHOED_VALUE) + "…";
        return "\"" + shown + "\"";
    }
}
