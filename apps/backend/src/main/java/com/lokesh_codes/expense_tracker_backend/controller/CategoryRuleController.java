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

import com.lokesh_codes.expense_tracker_backend.DTO.CategoryRuleDTO;
import com.lokesh_codes.expense_tracker_backend.entity.MatchType;
import com.lokesh_codes.expense_tracker_backend.service.CategoryRuleService;

import jakarta.validation.Valid;

/**
 * Filing rules for imported transactions.
 *
 * <p>Not paged. A user is capped at a hundred rules and the client shows them as
 * one ordered list, because the order is the meaning — paging it would hide the
 * only thing about a rule set that is hard to reason about.
 */
@RestController
@RequestMapping("/api/category-rules")
public class CategoryRuleController {

    private final CategoryRuleService ruleService;

    public CategoryRuleController(CategoryRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public ResponseEntity<List<CategoryRuleDTO>> getAll() {
        return ResponseEntity.ok(ruleService.getAll());
    }

    /**
     * The match types and their wording, so the client does not keep its own
     * copy of an enum that lives on the server.
     */
    @GetMapping("/match-types")
    public ResponseEntity<List<Map<String, String>>> matchTypes() {
        return ResponseEntity.ok(java.util.Arrays.stream(MatchType.values())
                .map(type -> Map.of("value", type.name(), "label", type.label()))
                .toList());
    }

    @PostMapping
    public ResponseEntity<CategoryRuleDTO> create(@Valid @RequestBody CategoryRuleDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryRuleDTO> update(@PathVariable Integer id,
            @Valid @RequestBody CategoryRuleDTO dto) {
        return ResponseEntity.ok(ruleService.update(id, dto));
    }

    /** Returns the whole list, since moving one rule renumbers the rest. */
    @PutMapping("/{id}/move-up")
    public ResponseEntity<List<CategoryRuleDTO>> moveUp(@PathVariable Integer id) {
        return ResponseEntity.ok(ruleService.move(id, true));
    }

    @PutMapping("/{id}/move-down")
    public ResponseEntity<List<CategoryRuleDTO>> moveDown(@PathVariable Integer id) {
        return ResponseEntity.ok(ruleService.move(id, false));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        ruleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
