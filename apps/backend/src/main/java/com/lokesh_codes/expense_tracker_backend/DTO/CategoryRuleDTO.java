package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.MatchType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryRuleDTO {

    private Integer id;

    @NotBlank(message = "Text to match is required")
    @Size(min = 2, max = 120, message = "Text to match must be between 2 and 120 characters")
    private String pattern;

    @NotNull(message = "Match type is required")
    private MatchType matchType;

    @NotNull(message = "Category is required")
    private Integer categoryId;

    /** Convenience for clients so a list does not need a second lookup. */
    private String categoryName;

    /** Lowest first. Assigned by the server when a rule is created. */
    private int priority;

    private boolean active = true;
}
