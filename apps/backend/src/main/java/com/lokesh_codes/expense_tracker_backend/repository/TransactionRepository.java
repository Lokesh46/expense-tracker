package com.lokesh_codes.expense_tracker_backend.repository;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Integer> {
    List<Transaction> findByUser_Id(Integer userId);
}
