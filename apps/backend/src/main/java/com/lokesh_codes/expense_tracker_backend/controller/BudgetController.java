package com.lokesh_codes.expense_tracker_backend.controller;

import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.BudgetDTO;
import com.lokesh_codes.expense_tracker_backend.service.BudgetService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * @param month which month to report spend against, as yyyy-MM.
     *              Defaults to the current month.
     */
    @GetMapping
    public ResponseEntity<List<BudgetDTO>> getAll(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(budgetService.getBudgets(month));
    }

    @PostMapping
    public ResponseEntity<BudgetDTO> create(@Valid @RequestBody BudgetDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.createBudget(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BudgetDTO> update(@PathVariable Integer id, @Valid @RequestBody BudgetDTO dto) {
        return ResponseEntity.ok(budgetService.updateBudget(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        budgetService.deleteBudget(id);
        return ResponseEntity.noContent().build();
    }
}
