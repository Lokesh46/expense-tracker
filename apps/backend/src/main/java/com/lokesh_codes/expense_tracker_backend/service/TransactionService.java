package com.lokesh_codes.expense_tracker_backend.service;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    // Create a new transaction
    public TransactionDTO createTransaction(TransactionDTO transactionDTO) {
        User user = getCurrentUser();
        var category = categoryRepository.findById(transactionDTO.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found"));

        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setCategory(category);
        transaction.setDescription(transactionDTO.getDescription());
        transaction.setAmount(transactionDTO.getAmount());
        transaction.setCurrency(transactionDTO.getCurrency());
        transaction.setDate(transactionDTO.getDate());
        transaction.setPaymentMethod(transactionDTO.getPaymentMethod());
        transaction.setComments(transactionDTO.getComments());

        Transaction savedTransaction = transactionRepository.save(transaction);
        return TransactionMapping.toDTO(savedTransaction);
    }

    // Update an existing transaction
    public TransactionDTO updateTransaction(Integer id, TransactionDTO transactionDTO) {
        var existingTransaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        User currentUser = getCurrentUser();
        if (!existingTransaction.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update this transaction");
        }
        existingTransaction.setDescription(transactionDTO.getDescription());
        existingTransaction.setAmount(transactionDTO.getAmount());
        existingTransaction.setCurrency(transactionDTO.getCurrency());
        existingTransaction.setDate(transactionDTO.getDate());
        existingTransaction.setPaymentMethod(transactionDTO.getPaymentMethod());
        existingTransaction.setComments(transactionDTO.getComments());

        Transaction updatedTransaction = transactionRepository.save(existingTransaction);
        return TransactionMapping.toDTO(updatedTransaction);
    }

    // Delete a transaction
    public void deleteTransaction(Integer id) {
        var existingTransaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        User currentUser = getCurrentUser();
        if (!existingTransaction.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to delete this transaction");
        }
        transactionRepository.delete(existingTransaction);
    }

    // Get transaction by ID
    public TransactionDTO getTransactionById(Integer id) {
        var transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        User currentUser = getCurrentUser();
        if (!transaction.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to view this transaction");
        }
        return TransactionMapping.toDTO(transaction);
    }

    // Get all transactions
    public List<TransactionDTO> getAllTransactions() {
        User currentUser = getCurrentUser();
        List<Transaction> transactions = transactionRepository.findByUser_Id(currentUser.getId());
        return transactions.stream()
                .map(TransactionMapping::toDTO)
                .collect(Collectors.toList());
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
