package com.lokesh_codes.expense_tracker_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.RecurringTransactionDTO;
import com.lokesh_codes.expense_tracker_backend.service.RecurringTransactionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/recurring")
public class RecurringTransactionController {

    private final RecurringTransactionService recurringService;

    public RecurringTransactionController(RecurringTransactionService recurringService) {
        this.recurringService = recurringService;
    }

    @GetMapping
    public ResponseEntity<List<RecurringTransactionDTO>> getAll() {
        return ResponseEntity.ok(recurringService.getAll());
    }

    @PostMapping
    public ResponseEntity<RecurringTransactionDTO> create(@Valid @RequestBody RecurringTransactionDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recurringService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringTransactionDTO> update(@PathVariable Integer id,
            @Valid @RequestBody RecurringTransactionDTO dto) {
        return ResponseEntity.ok(recurringService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        recurringService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Materialises anything already due, without waiting for the nightly sweep. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Integer>> runNow() {
        return ResponseEntity.ok(Map.of("created", recurringService.generateDueForCurrentUser()));
    }
}
