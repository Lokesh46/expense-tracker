package com.lokesh_codes.expense_tracker_backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;

public interface TransactionRepository
        extends JpaRepository<Transaction, Integer>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByUser_Id(Integer userId);

    Optional<Transaction> findByIdAndUser_Id(Integer id, Integer userId);

    /**
     * Total spend for one category over a date range, summed in the database
     * rather than by loading every row. Returns null when nothing matches.
     */
    @Query("""
            select sum(t.amount) from Transaction t
            where t.user.id = :userId
              and t.category.id = :categoryId
              and t.date between :from and :to
            """)
    BigDecimal sumForCategoryBetween(@Param("userId") Integer userId,
            @Param("categoryId") Integer categoryId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
