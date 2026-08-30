package com.lokesh_codes.expense_tracker_backend.DTO;

import java.math.BigDecimal;
import java.util.List;

import com.lokesh_codes.expense_tracker_backend.entity.CategorySource;

/**
 * One merchant's worth of transactions waiting to be approved or refiled.
 *
 * <p>The unit of review is the merchant rather than the row, which is the whole
 * point: a statement with two hundred rows and six merchants is six decisions.
 *
 * @param merchantHash          how the merchant is addressed when acting on the
 *                              group. A digest, not a name — the name is
 *                              encrypted and is not something to key an API on.
 * @param merchantName          what to show, e.g. {@code swiggy}
 * @param transactionCount      how many rows approving this would settle
 * @param totals                per currency, never summed across them
 * @param suggestedCategoryId   where these rows currently sit
 * @param suggestedCategoryName its name, so the client needs no second lookup
 * @param source                how that category was arrived at, so the screen
 *                              can distinguish a guess from a fallback
 * @param samples               the earliest and latest descriptions in the
 *                              group. Two rather than one: if the merchant key
 *                              has merged two different shops, these are the
 *                              likeliest pair to show it before you approve a
 *                              hundred rows at once.
 */
public record MerchantGroupDTO(String merchantHash,
        String merchantName,
        int transactionCount,
        List<CurrencyTotalDTO> totals,
        Integer suggestedCategoryId,
        String suggestedCategoryName,
        CategorySource source,
        List<String> samples) {

    /** A total in one currency. */
    public record CurrencyTotalDTO(String currency, BigDecimal amount) {
    }
}
