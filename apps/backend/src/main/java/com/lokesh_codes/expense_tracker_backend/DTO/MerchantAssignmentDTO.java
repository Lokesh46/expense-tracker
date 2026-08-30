package com.lokesh_codes.expense_tracker_backend.DTO;

import jakarta.validation.constraints.NotNull;

/**
 * Refiles every unreviewed row of one merchant.
 *
 * @param categoryId where they should go. Checked against the caller's own
 *                   categories before anything is written — that check is the
 *                   security boundary, not a validation nicety.
 * @param createRule whether to remember the decision as a filing rule, so the
 *                   merchant never comes back to the queue. Defaults on: a
 *                   correction you have to make every month is not a
 *                   correction.
 */
public record MerchantAssignmentDTO(@NotNull(message = "Category is required") Integer categoryId,
        Boolean createRule) {

    public boolean shouldCreateRule() {
        return createRule == null || createRule;
    }
}
