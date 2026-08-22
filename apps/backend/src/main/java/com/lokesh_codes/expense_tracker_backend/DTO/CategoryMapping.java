package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.Category;

public final class CategoryMapping {

    private CategoryMapping() {
    }

    public static CategoryDTO toDTO(Category category) {
        return new CategoryDTO(category.getId(), category.getName(), category.getColor());
    }
}
