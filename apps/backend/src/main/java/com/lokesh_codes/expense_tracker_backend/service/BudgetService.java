package com.lokesh_codes.expense_tracker_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.BudgetDTO;
import com.lokesh_codes.expense_tracker_backend.entity.Budget;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.BudgetRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

/**
 * Monthly spending caps per category.
 *
 * <p>A budget stores only its limit. Spend is derived from the transactions in
 * whichever month is being viewed, so past months stay truthful instead of
 * being rewritten when a limit changes.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;

    public BudgetService(BudgetRepository budgetRepository,
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            CurrentUserService currentUser) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    /** Every budget, with spend calculated for the given month. */
    @Transactional(readOnly = true)
    public List<BudgetDTO> getBudgets(YearMonth month) {
        YearMonth target = month == null ? YearMonth.now() : month;
        Integer userId = currentUser.requireId();

        return budgetRepository.findByUser_Id(userId).stream()
                .map(budget -> toDTO(budget, target, userId))
                .sorted(Comparator.comparing(BudgetDTO::getCategoryName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Transactional
    public BudgetDTO createBudget(BudgetDTO dto) {
        User user = currentUser.require();
        Category category = categoryService.requireOwned(dto.getCategoryId());

        // One budget per category keeps "am I over?" unambiguous.
        budgetRepository.findByUser_IdAndCategory_Id(user.getId(), category.getId())
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "\"" + category.getName() + "\" already has a budget. Edit it instead.");
                });

        Budget budget = new Budget();
        budget.setUser(user);
        budget.setCategory(category);
        budget.setMonthlyLimit(dto.getMonthlyLimit());

        return toDTO(budgetRepository.save(budget), YearMonth.now(), user.getId());
    }

    @Transactional
    public BudgetDTO updateBudget(Integer id, BudgetDTO dto) {
        Integer userId = currentUser.requireId();
        Budget budget = budgetRepository.findByIdAndUser_Id(id, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found"));

        budget.setMonthlyLimit(dto.getMonthlyLimit());
        return toDTO(budgetRepository.save(budget), YearMonth.now(), userId);
    }

    @Transactional
    public void deleteBudget(Integer id) {
        Budget budget = budgetRepository.findByIdAndUser_Id(id, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Budget not found"));
        budgetRepository.delete(budget);
    }

    private BudgetDTO toDTO(Budget budget, YearMonth month, Integer userId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        BigDecimal spent = transactionRepository.sumForCategoryBetween(
                userId, budget.getCategory().getId(), from, to);
        if (spent == null) {
            spent = BigDecimal.ZERO;
        }
        spent = spent.setScale(2, RoundingMode.HALF_UP);

        BigDecimal limit = budget.getMonthlyLimit();
        // Remaining is floored at zero: "how much is left" is never negative, and
        // the overspend is already visible through percentUsed and exceeded.
        BigDecimal remaining = limit.subtract(spent).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        BigDecimal percentUsed = limit.signum() == 0
                ? BigDecimal.ZERO
                : spent.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);

        return new BudgetDTO(
                budget.getId(),
                budget.getCategory().getId(),
                budget.getCategory().getName(),
                limit,
                spent,
                remaining,
                percentUsed,
                spent.compareTo(limit) > 0);
    }
}
