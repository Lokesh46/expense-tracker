package com.lokesh_codes.expense_tracker_backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lokesh_codes.expense_tracker_backend.entity.Budget;

public interface BudgetRepository extends JpaRepository<Budget, Integer> {

    List<Budget> findByUser_Id(Integer userId);

    Optional<Budget> findByIdAndUser_Id(Integer id, Integer userId);

    Optional<Budget> findByUser_IdAndCategory_Id(Integer userId, Integer categoryId);
}
