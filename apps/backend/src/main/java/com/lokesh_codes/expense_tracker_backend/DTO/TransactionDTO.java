package com.lokesh_codes.expense_tracker_backend.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lokesh_codes.expense_tracker_backend.entity.CategorySource;
import com.lokesh_codes.expense_tracker_backend.entity.TransactionType;

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
public class TransactionDTO {

    private Integer id;

    /** Set by the server from the authenticated user; ignored on input. */
    private Integer userId;

    @NotNull(message = "Category is required")
    private Integer categoryId;

    /** Convenience for clients so a list does not need a second lookup. */
    private String categoryName;

    @NotBlank(message = "Description is required")
    @Size(min = 2, max = 200, message = "Description must be between 2 and 200 characters")
    private String description;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount may have at most two decimal places")
    private BigDecimal amount;

    /**
     * Money out or money in. Absent means an expense, so a client written before
     * this existed keeps working and every stored row keeps its meaning.
     */
    private TransactionType type = TransactionType.EXPENSE;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a three-letter code")
    private String currency;

    @NotNull(message = "Date is required")
    private LocalDate date;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @Size(max = 500, message = "Comments may be at most 500 characters")
    private String comments;

    /**
     * Whether an import thought this row looked like one already on file. Set by
     * the server; a value sent by a client is ignored, since marking your own
     * row as a duplicate is what the review endpoint is for.
     */
    private boolean possibleDuplicate;

    /**
     * Whether the owner has agreed with the category, or an import guessed it and
     * is still waiting to be told. Set by the server; a client agrees with a
     * category through the review endpoints, not by asserting it here.
     */
    private boolean categoryConfirmed = true;

    /** How the category was arrived at, so a surprising one can explain itself. */
    private CategorySource categorySource = CategorySource.MANUAL;
}
