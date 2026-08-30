package com.lokesh_codes.expense_tracker_backend.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.CategoryRuleDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.MerchantAssignmentDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.MerchantGroupDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.MerchantGroupDTO.CurrencyTotalDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ReviewActionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ReviewQueueDTO;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.CategoryRule;
import com.lokesh_codes.expense_tracker_backend.entity.CategorySource;
import com.lokesh_codes.expense_tracker_backend.entity.MatchType;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

/**
 * The queue of imported rows whose category was guessed rather than known, and
 * the two things that can be done about one.
 *
 * <p>Grouped by merchant throughout. Reviewing row by row is what makes people
 * abandon categorisation altogether — two hundred rows is two hundred decisions
 * — and the same two hundred rows are usually a handful of shops.
 *
 * <p>Approving or refiling a merchant confirms its rows, and confirmed rows are
 * the only ones the next import learns from. That is what makes the queue shrink
 * with use rather than arriving identical every month.
 */
@Service
public class CategoryReviewService {

    /**
     * How many groups one response carries.
     *
     * <p>A cap rather than paging: past a hundred merchants the screen is not
     * the problem, the merchant key is, and the honest response is to say how
     * many were left out. The count is always reported, so a truncated list
     * never reads as a finished one.
     */
    private static final int MAX_GROUPS = 100;

    private final TransactionRepository transactions;
    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final CategoryRuleService ruleService;
    private final CurrentUserService currentUser;

    public CategoryReviewService(TransactionRepository transactions,
            CategoryRepository categoryRepository,
            CategoryService categoryService,
            CategoryRuleService ruleService,
            CurrentUserService currentUser) {
        this.transactions = transactions;
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
        this.ruleService = ruleService;
        this.currentUser = currentUser;
    }

    /** Counts alone, for a navigation badge that must not cost a full listing. */
    @Transactional(readOnly = true)
    public ReviewQueueDTO summary() {
        Integer userId = currentUser.requireId();
        return new ReviewQueueDTO(List.of(),
                transactions.countUnconfirmedMerchants(userId),
                transactions.countUnconfirmed(userId));
    }

    /**
     * The queue, largest group first.
     *
     * <p>Two queries and one bounded load, never one query per group. The counts
     * and totals are grouped in the database; the labels need rows, because a
     * merchant name is encrypted and cannot be read any other way, so exactly
     * two rows per shown group are fetched for that.
     */
    @Transactional(readOnly = true)
    public ReviewQueueDTO merchants() {
        Integer userId = currentUser.requireId();

        Map<String, Group> groups = new HashMap<>();
        for (Object[] row : transactions.findUnconfirmedGroups(userId)) {
            String hash = (String) row[0];
            Integer categoryId = (Integer) row[1];
            String currency = (String) row[2];
            long count = ((Number) row[3]).longValue();
            BigDecimal total = (BigDecimal) row[4];

            groups.computeIfAbsent(hash, key -> new Group()).add(categoryId, currency, count, total);
        }
        if (groups.isEmpty()) {
            return new ReviewQueueDTO(List.of(), 0, 0);
        }

        List<Map.Entry<String, Group>> largest = groups.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Group>>comparingLong(e -> e.getValue().count)
                        .reversed())
                .limit(MAX_GROUPS)
                .toList();

        Map<String, List<Transaction>> samples = samplesFor(userId,
                largest.stream().map(Map.Entry::getKey).toList());

        Map<Integer, Category> categories = new HashMap<>();
        categoryRepository.findByUser_IdOrderByNameAsc(userId)
                .forEach(category -> categories.put(category.getId(), category));

        List<MerchantGroupDTO> merchants = new ArrayList<>(largest.size());
        long waiting = 0;

        for (Map.Entry<String, Group> entry : largest) {
            Group group = entry.getValue();
            List<Transaction> rows = samples.getOrDefault(entry.getKey(), List.of());
            waiting += group.count;

            // The category most of the group sits in, not whichever one the
            // sample row happens to have: a merchant imported twice can have
            // picked up two different guesses, and the majority is the one the
            // screen should offer.
            Category category = categories.get(group.dominantCategory());

            merchants.add(new MerchantGroupDTO(
                    entry.getKey(),
                    rows.isEmpty() ? "Unknown" : rows.get(0).getMerchantName(),
                    (int) group.count,
                    group.totals(),
                    category == null ? null : category.getId(),
                    category == null ? null : category.getName(),
                    rows.isEmpty() ? CategorySource.NONE : rows.get(0).getCategorySource(),
                    rows.stream().map(Transaction::getDescription).distinct().toList()));
        }

        long merchantsTotal = groups.size();
        // The waiting count covers the groups shown; anything beyond the cap is
        // still counted, so the header does not shrink when the list is cut.
        long transactionsTotal = merchantsTotal > merchants.size()
                ? transactions.countUnconfirmed(userId)
                : waiting;

        return new ReviewQueueDTO(merchants, merchantsTotal, transactionsTotal);
    }

    /**
     * Accepts the suggested category for a merchant, unchanged.
     *
     * <p>The rows were already filed there; what changes is that it now counts as
     * a decision, which is what future imports learn from.
     */
    @Transactional
    public ReviewActionDTO approve(String merchantHash) {
        int updated = transactions.confirmMerchant(currentUser.requireId(), merchantHash);
        requireSomethingToDo(updated);
        return new ReviewActionDTO(updated, false, wording(updated) + " approved.");
    }

    /** Accepts every suggestion at once. */
    @Transactional
    public ReviewActionDTO approveAll() {
        int updated = transactions.confirmAllFor(currentUser.requireId());
        requireSomethingToDo(updated);
        return new ReviewActionDTO(updated, false, wording(updated) + " approved.");
    }

    /**
     * Refiles every unreviewed row of a merchant, and optionally remembers it.
     *
     * <p>Rows already confirmed are untouched. They are decisions their owner has
     * made, and a correction is about what the importer guessed — not a licence
     * to rewrite last year.
     */
    @Transactional
    public ReviewActionDTO assign(String merchantHash, MerchantAssignmentDTO request) {
        Integer userId = currentUser.requireId();

        // Resolved through the caller's own categories. A category belonging to
        // somebody else must not be assignable, and must not be distinguishable
        // from one that does not exist.
        Category category = categoryService.requireOwned(request.categoryId());

        // Read before the update: confirming the rows is exactly what stops them
        // matching this query, so the name has to be taken while it is still
        // reachable.
        String merchantName = transactions
                .findFirstByUser_IdAndMerchantHashAndCategoryConfirmedFalse(userId, merchantHash)
                .map(Transaction::getMerchantName)
                .orElse(null);

        int updated = transactions.assignMerchant(userId, merchantHash, category);
        requireSomethingToDo(updated);

        RuleOutcome rule = request.shouldCreateRule()
                ? rememberRule(userId, merchantName, category)
                : new RuleOutcome(false, "");

        return new ReviewActionDTO(updated, rule.created(),
                wording(updated) + " moved to " + category.getName() + "." + rule.note());
    }

    /**
     * Writes a filing rule so this merchant never reaches the queue again.
     *
     * <p>Failure here never fails the refiling. The rows are the point; the rule
     * is a convenience, and losing a correction because a rule list was full
     * would be an absurd trade.
     */
    private RuleOutcome rememberRule(Integer userId, String merchantName, Category category) {
        if (merchantName == null || merchantName.isBlank()) {
            return new RuleOutcome(false,
                    " There was no merchant name to build a rule from, so this was a one-off.");
        }

        Optional<CategoryRule> existing = ruleService.activeRulesFor(userId).stream()
                .filter(rule -> rule.getPattern().equalsIgnoreCase(merchantName))
                .findFirst();
        if (existing.isPresent()) {
            // Already covered. A second identical rule would never be reached.
            return new RuleOutcome(false, " A rule for " + merchantName + " already exists.");
        }

        CategoryRuleDTO rule = new CategoryRuleDTO();
        rule.setPattern(merchantName);
        rule.setMatchType(MatchType.CONTAINS);
        rule.setCategoryId(category.getId());
        rule.setActive(true);

        try {
            ruleService.create(rule);
            return new RuleOutcome(true, " " + merchantName + " will be filed there from now on.");
        } catch (ConflictException e) {
            return new RuleOutcome(false,
                    " Your rule list is full, so this was not remembered for next time.");
        }
    }

    private Map<String, List<Transaction>> samplesFor(Integer userId, List<String> hashes) {
        List<Integer> ids = new ArrayList<>(hashes.size() * 2);
        for (Object[] row : transactions.findUnconfirmedSampleIds(userId)) {
            if (hashes.contains((String) row[0])) {
                ids.add(((Number) row[1]).intValue());
                ids.add(((Number) row[2]).intValue());
            }
        }

        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction row : transactions.findAllById(ids)) {
            byMerchant.computeIfAbsent(row.getMerchantHash(), key -> new ArrayList<>()).add(row);
        }
        return byMerchant;
    }

    /**
     * A merchant with nothing waiting reports "not found" rather than reporting
     * success over zero rows, which would let the screen say something was done
     * when it was not — and would let a digest be probed for existence.
     */
    private void requireSomethingToDo(int updated) {
        if (updated == 0) {
            throw new NotFoundException("There is nothing left to review here.");
        }
    }

    private String wording(int updated) {
        return updated + (updated == 1 ? " transaction" : " transactions");
    }

    private record RuleOutcome(boolean created, String note) {
    }

    /** One merchant's tallies, accumulated as the grouped rows arrive. */
    private static final class Group {

        private long count;
        private final Map<Integer, Long> byCategory = new HashMap<>();
        private final Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();

        void add(Integer categoryId, String currency, long rows, BigDecimal total) {
            count += rows;
            byCategory.merge(categoryId, rows, Long::sum);
            byCurrency.merge(currency == null ? "" : currency,
                    total == null ? BigDecimal.ZERO : total, BigDecimal::add);
        }

        List<CurrencyTotalDTO> totals() {
            return byCurrency.entrySet().stream()
                    .map(entry -> new CurrencyTotalDTO(entry.getKey(), entry.getValue()))
                    .toList();
        }

        Integer dominantCategory() {
            return byCategory.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }
}
