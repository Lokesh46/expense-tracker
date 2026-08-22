package com.lokesh_codes.expense_tracker_backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.lokesh_codes.expense_tracker_backend.DTO.ImportResultDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

/** CSV import and export for transactions. */
@Service
public class TransactionCsvService {

    static final String HEADER = "Date,Description,Category,Amount,Currency,Payment Method,Comments";

    /** The layouts seen most often in exported bank statements. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    /** Guards against a huge upload being held entirely in memory. */
    private static final int MAX_ROWS = 10_000;

    private static final String DEFAULT_COLOR = "#6366f1";

    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CurrentUserService currentUser;

    public TransactionCsvService(TransactionService transactionService,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            CurrentUserService currentUser) {
        this.transactionService = transactionService;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.currentUser = currentUser;
    }

    /** Exports every transaction matching the filter, newest first. */
    @Transactional(readOnly = true)
    public String export(TransactionFilter filter) {
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
                    t.getComments()));
        }
        return csv.toString();
    }

    /**
     * Imports transactions from a CSV file.
     *
     * <p>Each row is validated on its own and a bad row is reported rather than
     * aborting the file, so one malformed line in a bank export does not discard
     * everything else. Category names that do not exist yet are created.
     */
    @Transactional
    public ImportResultDTO importCsv(MultipartFile file) throws IOException {
        User user = currentUser.require();

        Map<String, Category> categoriesByName = new HashMap<>();
        categoryRepository.findByUser_IdOrderByNameAsc(user.getId())
                .forEach(category -> categoriesByName.put(lower(category.getName()), category));

        List<String> errors = new ArrayList<>();
        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    continue;
                }
                // Skip a header row if one is present, whatever its capitalisation.
                if (lineNumber == 1 && lower(line).startsWith("date,")) {
                    continue;
                }
                if (toSave.size() >= MAX_ROWS) {
                    errors.add("Stopped at " + MAX_ROWS
                            + " rows. Split the file and import the rest separately.");
                    break;
                }

                try {
                    toSave.add(parseRow(CsvSupport.parseLine(line), user, categoriesByName));
                } catch (IllegalArgumentException e) {
                    skipped++;
                    // Report the first handful only; a wrongly-mapped file would
                    // otherwise produce one error per row.
                    if (errors.size() < 20) {
                        errors.add("Line " + lineNumber + ": " + e.getMessage());
                    }
                }
            }
        }

        transactionRepository.saveAll(toSave);
        return new ImportResultDTO(toSave.size(), skipped, errors);
    }

    private Transaction parseRow(List<String> fields, User user, Map<String, Category> categoriesByName) {
        if (fields.size() < 4) {
            throw new IllegalArgumentException(
                    "expected at least Date, Description, Category and Amount");
        }

        LocalDate date = parseDate(fields.get(0));

        String description = fields.get(1);
        if (description.isBlank()) {
            throw new IllegalArgumentException("description is empty");
        }

        String categoryName = fields.get(2).isBlank() ? "Other" : fields.get(2);
        BigDecimal amount = parseAmount(fields.get(3));

        Category category = categoriesByName.computeIfAbsent(lower(categoryName),
                key -> categoryRepository.save(new Category(user, categoryName, DEFAULT_COLOR)));

        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setCategory(category);
        transaction.setDate(date);
        transaction.setDescription(description);
        transaction.setAmount(amount);
        transaction.setCurrency(value(fields, 4, "USD").toUpperCase());
        transaction.setPaymentMethod(value(fields, 5, "Other"));
        transaction.setComments(value(fields, 6, ""));
        return transaction;
    }

    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // Try the next known layout.
            }
        }
        throw new IllegalArgumentException(quote(raw) + " is not a date we recognise");
    }

    private BigDecimal parseAmount(String raw) {
        // Tolerate currency symbols, thousands separators and parenthesised negatives.
        String cleaned = raw.replaceAll("[^0-9.()-]", "").trim();

        boolean parenthesised = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (parenthesised) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(quote(raw) + " is not an amount");
        }

        try {
            // Expenses are stored as positive amounts; statements often mark them
            // as debits with a leading minus or surrounding brackets.
            BigDecimal amount = new BigDecimal(cleaned).abs().setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("amount must be greater than zero");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(quote(raw) + " is not an amount");
        }
    }

    private String value(List<String> fields, int index, String fallback) {
        if (index >= fields.size() || fields.get(index).isBlank()) {
            return fallback;
        }
        return fields.get(index);
    }

    private String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }
}
