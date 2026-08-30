package com.lokesh_codes.expense_tracker_backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lokesh_codes.expense_tracker_backend.entity.Category;
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
     * Rows written before merchants were derived. The same one-time backfill
     * handles these; see {@code TransactionIndexer.NO_MERCHANT} for why a row
     * with no merchant in it still gets a value rather than staying null.
     */
    List<Transaction> findByMerchantHashIsNull(Pageable pageable);

    /**
     * Where this account has filed each of these merchants before, and how often.
     *
     * <p>Confirmed rows only, and that is the whole safety property. Counting
     * rows the importer guessed at would let one wrong guess stand as evidence
     * for itself, and a merchant misfiled once would stay misfiled forever with
     * growing confidence.
     *
     * <p>Returns {@code [merchantHash, categoryId, count]}. Restricted to the
     * merchants actually present in the file being imported, so the work is
     * proportional to the file rather than to the ledger.
     */
    @Query("""
            select t.merchantHash, t.category.id, count(t) from Transaction t
            where t.user.id = :userId
              and t.merchantHash in :hashes
              and t.categoryConfirmed = true
            group by t.merchantHash, t.category.id
            """)
    List<Object[]> findConfirmedMerchantCategories(@Param("userId") Integer userId,
            @Param("hashes") Collection<String> hashes);

    /**
     * The review queue, grouped and totalled in the database.
     *
     * <p>Returns {@code [merchantHash, categoryId, currency, count, sum]}. The
     * currency is part of the grouping because totals are never summed across
     * currencies — a figure that adds pounds to rupees is worse than no figure.
     */
    @Query("""
            select t.merchantHash, t.category.id, t.currency, count(t), sum(t.amount)
            from Transaction t
            where t.user.id = :userId and t.categoryConfirmed = false
            group by t.merchantHash, t.category.id, t.currency
            """)
    List<Object[]> findUnconfirmedGroups(@Param("userId") Integer userId);

    /**
     * The first and last row of each unconfirmed merchant group.
     *
     * <p>Two rather than one: the merchant name is encrypted, so a group needs a
     * row loaded to be labelled at all, and if the key has merged two different
     * shops the earliest and latest descriptions are the likeliest pair to show
     * it. Returns {@code [merchantHash, minId, maxId]}.
     */
    @Query("""
            select t.merchantHash, min(t.id), max(t.id) from Transaction t
            where t.user.id = :userId and t.categoryConfirmed = false
            group by t.merchantHash
            """)
    List<Object[]> findUnconfirmedSampleIds(@Param("userId") Integer userId);

    /**
     * Any one unreviewed row of a merchant, for its name.
     *
     * <p>The name is encrypted, so it cannot be selected or grouped by — a row
     * has to be loaded and passed back through the converter to read it.
     */
    Optional<Transaction> findFirstByUser_IdAndMerchantHashAndCategoryConfirmedFalse(
            Integer userId, String merchantHash);

    @Query("""
            select count(t) from Transaction t
            where t.user.id = :userId and t.categoryConfirmed = false
            """)
    long countUnconfirmed(@Param("userId") Integer userId);

    @Query("""
            select count(distinct t.merchantHash) from Transaction t
            where t.user.id = :userId and t.categoryConfirmed = false
            """)
    long countUnconfirmedMerchants(@Param("userId") Integer userId);

    /**
     * Accepts the suggested category for every unconfirmed row of one merchant.
     *
     * <p>A bulk update rather than a load-and-save loop: approving a merchant can
     * touch hundreds of rows, and nothing derived depends on the category —
     * {@code TransactionFingerprint} deliberately leaves it out — so there is
     * nothing to recompute.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Transaction t set t.categoryConfirmed = true
            where t.user.id = :userId
              and t.merchantHash = :hash
              and t.categoryConfirmed = false
            """)
    int confirmMerchant(@Param("userId") Integer userId, @Param("hash") String hash);

    /**
     * Refiles every unconfirmed row of one merchant.
     *
     * <p>Confirmed rows are left alone deliberately. They are decisions their
     * owner has already made, and a remap is about what the importer guessed,
     * not about rewriting history.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Transaction t
            set t.category = :category,
                t.categoryConfirmed = true,
                t.categorySource = com.lokesh_codes.expense_tracker_backend.entity.CategorySource.MANUAL
            where t.user.id = :userId
              and t.merchantHash = :hash
              and t.categoryConfirmed = false
            """)
    int assignMerchant(@Param("userId") Integer userId, @Param("hash") String hash,
            @Param("category") Category category);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Transaction t set t.categoryConfirmed = true
            where t.user.id = :userId and t.categoryConfirmed = false
            """)
    int confirmAllFor(@Param("userId") Integer userId);

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
