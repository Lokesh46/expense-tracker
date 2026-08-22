package com.lokesh_codes.expense_tracker_backend.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lokesh_codes.expense_tracker_backend.entity.Frequency;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecurringTransactionDTO {

    private Integer id;

    @NotNull(message = "Category is required")
    private Integer categoryId;

    private String categoryName;

    @NotBlank(message = "Description is required")
    @Size(min = 2, max = 200, message = "Description must be between 2 and 200 characters")
    private String description;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount may have at most two decimal places")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a three-letter code")
    private String currency;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @Size(max = 500, message = "Comments may be at most 500 characters")
    private String comments;

    @NotNull(message = "Frequency is required")
    private Frequency frequency;

    @NotNull(message = "A start date is required")
    private LocalDate nextRunDate;

    private LocalDate endDate;

    private boolean active = true;
}
