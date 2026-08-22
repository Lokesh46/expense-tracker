package com.lokesh_codes.expense_tracker_backend.DTO;

import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.entity.User;

public class TransactionMapping {

    public static TransactionDTO toDTO(Transaction transaction) {
        return new TransactionDTO(
                transaction.getId(),
                transaction.getUser().getId(),
                transaction.getCategory().getId(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDate(),
                transaction.getPaymentMethod(),
                transaction.getComments()
        );
    }

//    public static Transaction toEntity(TransactionDTO transactionDTO) {
//        // In a real-world app, you'd need to fetch the User and Category by their ids from the database
//        return new Transaction(
//                transactionDTO.getId(),
//                new User(transactionDTO.getId(), null, null, null, false),  // Fetch user based on ID
//                new Category(transactionDTO.getCategoryId(), null),  // Fetch category based on ID
//                transactionDTO.getAmount(),
//                transactionDTO.getCurrency(),
//                transactionDTO.getDate(),
//                transactionDTO.getPaymentMethod(),
//                transactionDTO.getComments()
//        );
//    }
}
