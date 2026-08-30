package com.lokesh_codes.expense_tracker_backend.DTO;

/**
 * What one review action actually did.
 *
 * @param updated     rows settled
 * @param ruleCreated whether a filing rule was written as well
 * @param message     wording for the client, covering the case where the rows
 *                    were refiled but the rule was not written — because the
 *                    rule list is full, or one already covers this merchant.
 *                    Losing a remap because of either would be absurd, so the
 *                    action succeeds and says so.
 */
public record ReviewActionDTO(int updated, boolean ruleCreated, String message) {
}
