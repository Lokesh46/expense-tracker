package com.lokesh_codes.expense_tracker_backend.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lokesh_codes.expense_tracker_backend.entity.RecurringTransaction;

public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, Integer> {

    List<RecurringTransaction> findByUser_Id(Integer userId);

    long countByUser_Id(Integer userId);

    void deleteByUser_Id(Integer userId);

    Optional<RecurringTransaction> findByIdAndUser_Id(Integer id, Integer userId);

    /** Rules that are due; used by the generator and on-demand catch-up. */
    List<RecurringTransaction> findByActiveTrueAndNextRunDateLessThanEqual(LocalDate date);

    List<RecurringTransaction> findByUser_IdAndActiveTrueAndNextRunDateLessThanEqual(Integer userId, LocalDate date);
}
