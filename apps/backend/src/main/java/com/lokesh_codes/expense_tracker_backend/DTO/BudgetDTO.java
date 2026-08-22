package com.lokesh_codes.expense_tracker_backend.DTO;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BudgetDTO {

    private Integer id;

    @NotNull(message = "Category is required")
    private Integer categoryId;

    private String categoryName;

    @NotNull(message = "A monthly limit is required")
    @DecimalMin(value = "0.01", message = "The limit must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "The limit may have at most two decimal places")
    private BigDecimal monthlyLimit;

    // --- computed for the month being viewed; ignored on input ---

    private BigDecimal spent;
    private BigDecimal remaining;

    /** Percentage of the limit used. Can exceed 100 when overspent. */
    private BigDecimal percentUsed;

    private boolean exceeded;
}
