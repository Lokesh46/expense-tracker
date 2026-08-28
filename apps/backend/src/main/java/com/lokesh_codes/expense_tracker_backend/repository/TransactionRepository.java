package com.lokesh_codes.expense_tracker_backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lokesh_codes.expense_tracker_backend.entity.Transaction;

public interface TransactionRepository
        extends JpaRepository<Transaction, Integer>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByUser_Id(Integer userId);

    long countByUser_Id(Integer userId);

    void deleteByUser_Id(Integer userId);

    Optional<Transaction> findByIdAndUser_Id(Integer id, Integer userId);

    /**
     * Rows written before field encryption existed, which therefore have no
     * search index yet. Used by the one-time backfill; the page is always the
     * first, because rewriting a row removes it from this result.
     */
    List<Transaction> findBySearchTokensIsNull(Pageable pageable);

    /**
     * The fingerprints already on file for one account over a date range.
     *
     * <p>Only the digests are selected, not the rows. An import needs to know
     * whether it has seen a transaction before, not what that transaction said,
     * and loading a few thousand entities to answer that would defeat the point.
     */
    @Query("""
            select t.fingerprint from Transaction t
            where t.user.id = :userId
              and t.date between :from and :to
              and t.fingerprint is not null
            """)
    List<String> findFingerprintsBetween(@Param("userId") Integer userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Total spend for one category over a date range, summed in the database
     * rather than by loading every row. Returns null when nothing matches.
     *
     * <p>Expenses only. A refund filed against the same category is money coming
     * back, and counting it towards a budget would report spending that did not
     * happen.
     */
    @Query("""
            select sum(t.amount) from Transaction t
            where t.user.id = :userId
              and t.category.id = :categoryId
              and t.date between :from and :to
              and t.type = com.lokesh_codes.expense_tracker_backend.entity.TransactionType.EXPENSE
            """)
    BigDecimal sumForCategoryBetween(@Param("userId") Integer userId,
            @Param("categoryId") Integer categoryId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
