package com.lokesh_codes.expense_tracker_backend.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.RecurringTransactionDTO;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.RecurringTransaction;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.RecurringTransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

/**
 * Rules that generate transactions on a schedule — rent, subscriptions,
 * standing orders.
 */
@Service
public class RecurringTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionService.class);

    /**
     * Ceiling on how many transactions one rule may produce in a single pass.
     *
     * <p>A daily rule left dormant for years would otherwise generate thousands
     * of rows in one request. Hitting the cap leaves {@code nextRunDate} where it
     * is, so the remainder is produced on the following run rather than lost.
     */
    private static final int MAX_CATCH_UP_PER_RULE = 400;

    private final RecurringTransactionRepository recurringRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;

    public RecurringTransactionService(RecurringTransactionRepository recurringRepository,
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            CurrentUserService currentUser) {
        this.recurringRepository = recurringRepository;
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<RecurringTransactionDTO> getAll() {
        return recurringRepository.findByUser_Id(currentUser.requireId())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public RecurringTransactionDTO create(RecurringTransactionDTO dto) {
        User user = currentUser.require();
        Category category = categoryService.requireOwned(dto.getCategoryId());

        RecurringTransaction rule = new RecurringTransaction();
        rule.setUser(user);
        apply(rule, dto, category);
        rule.setActive(true);

        return toDTO(recurringRepository.save(rule));
    }

    @Transactional
    public RecurringTransactionDTO update(Integer id, RecurringTransactionDTO dto) {
        RecurringTransaction rule = requireOwned(id);
        Category category = categoryService.requireOwned(dto.getCategoryId());
        apply(rule, dto, category);
        rule.setActive(dto.isActive());
        return toDTO(recurringRepository.save(rule));
    }

    @Transactional
    public void delete(Integer id) {
        recurringRepository.delete(requireOwned(id));
    }

    /**
     * Materialises everything the current user's rules owe up to today.
     *
     * <p>Called when the user loads their transactions, so entries appear even if
     * the scheduled sweep has not run — which is the normal case for a service
     * that sleeps between requests.
     *
     * @return how many transactions were created
     */
    @Transactional
    public int generateDueForCurrentUser() {
        Integer userId = currentUser.requireId();
        return generate(recurringRepository
                .findByUser_IdAndActiveTrueAndNextRunDateLessThanEqual(userId, LocalDate.now()));
    }

    /** Nightly sweep so rules still fire for users who are not signed in. */
    @Scheduled(cron = "${app.recurring.cron:0 15 2 * * *}")
    @Transactional
    public void generateDueForEveryone() {
        int created = generate(
                recurringRepository.findByActiveTrueAndNextRunDateLessThanEqual(LocalDate.now()));
        if (created > 0) {
            log.info("Generated {} transaction(s) from recurring rules", created);
        }
    }

    private int generate(List<RecurringTransaction> dueRules) {
        LocalDate today = LocalDate.now();
        int created = 0;

        for (RecurringTransaction rule : dueRules) {
            LocalDate due = rule.getNextRunDate();
            int producedForThisRule = 0;

            // Step one period at a time so a rule dormant for months produces every
            // transaction it missed, not a single lump entry.
            while (!due.isAfter(today) && producedForThisRule < MAX_CATCH_UP_PER_RULE) {
                if (rule.getEndDate() != null && due.isAfter(rule.getEndDate())) {
                    rule.setActive(false);
                    break;
                }

                transactionRepository.save(fromRule(rule, due));
                producedForThisRule++;
                created++;

                due = rule.getFrequency().advance(due);
            }

            rule.setNextRunDate(due);
            recurringRepository.save(rule);
        }

        return created;
    }

    private Transaction fromRule(RecurringTransaction rule, LocalDate date) {
        Transaction transaction = new Transaction();
        transaction.setUser(rule.getUser());
        transaction.setCategory(rule.getCategory());
        transaction.setDescription(rule.getDescription());
        transaction.setAmount(rule.getAmount());
        transaction.setCurrency(rule.getCurrency());
        transaction.setDate(date);
        transaction.setPaymentMethod(rule.getPaymentMethod());
        transaction.setComments(rule.getComments());
        return transaction;
    }

    private void apply(RecurringTransaction rule, RecurringTransactionDTO dto, Category category) {
        rule.setCategory(category);
        rule.setDescription(dto.getDescription().trim());
        rule.setAmount(dto.getAmount());
        rule.setCurrency(dto.getCurrency().toUpperCase());
        rule.setPaymentMethod(dto.getPaymentMethod());
        rule.setComments(dto.getComments());
        rule.setFrequency(dto.getFrequency());
        rule.setNextRunDate(dto.getNextRunDate());
        rule.setEndDate(dto.getEndDate());
    }

    private RecurringTransaction requireOwned(Integer id) {
        return recurringRepository.findByIdAndUser_Id(id, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Recurring transaction not found"));
    }

    private RecurringTransactionDTO toDTO(RecurringTransaction rule) {
        return new RecurringTransactionDTO(
                rule.getId(),
                rule.getCategory().getId(),
                rule.getCategory().getName(),
                rule.getDescription(),
                rule.getAmount(),
                rule.getCurrency(),
                rule.getPaymentMethod(),
                rule.getComments(),
                rule.getFrequency(),
                rule.getNextRunDate(),
                rule.getEndDate(),
                rule.isActive());
    }
}
