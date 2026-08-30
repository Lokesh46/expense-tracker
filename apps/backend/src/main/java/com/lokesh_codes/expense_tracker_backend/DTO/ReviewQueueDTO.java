package com.lokesh_codes.expense_tracker_backend.DTO;

import java.util.List;

/**
 * The review queue as a whole.
 *
 * @param merchants      the largest groups first, capped
 * @param merchantsTotal how many groups there are altogether. Reported rather
 *                       than left to be inferred from the list: a truncated
 *                       list that looks complete would say "you are finished"
 *                       when you are not.
 * @param transactions   how many rows are waiting, across every group
 */
public record ReviewQueueDTO(List<MerchantGroupDTO> merchants,
        long merchantsTotal,
        long transactions) {
}
