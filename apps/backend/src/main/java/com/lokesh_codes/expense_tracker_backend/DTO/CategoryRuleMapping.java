package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.CategoryRule;

public final class CategoryRuleMapping {

    private CategoryRuleMapping() {
    }

    public static CategoryRuleDTO toDTO(CategoryRule rule) {
        return new CategoryRuleDTO(
                rule.getId(),
                rule.getPattern(),
                rule.getMatchType(),
                rule.getCategory().getId(),
                rule.getCategory().getName(),
                rule.getPriority(),
                rule.isActive());
    }
}
