package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;

public final class TransactionMapping {

    private TransactionMapping() {
    }

    public static TransactionDTO toDTO(Transaction transaction) {
        return new TransactionDTO(
                transaction.getId(),
                transaction.getUser().getId(),
                transaction.getCategory().getId(),
                transaction.getCategory().getName(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDate(),
                transaction.getPaymentMethod(),
                transaction.getComments());
    }
}
