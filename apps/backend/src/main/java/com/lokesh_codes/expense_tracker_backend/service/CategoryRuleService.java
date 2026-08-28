package com.lokesh_codes.expense_tracker_backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.CategoryRuleDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.CategoryRuleMapping;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.CategoryRule;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRuleRepository;

/**
 * Manages a user's filing rules.
 *
 * <p>Every method resolves the rule and its category through the current user,
 * so a rule can only ever point at a category the same account owns. That check
 * is the security boundary: without it, a rule is a way to read whether somebody
 * else's category id exists.
 */
@Service
public class CategoryRuleService {

    /**
     * Rules are evaluated in order for every imported row, so the list is a
     * per-import cost. A hundred is far more than anyone files by hand and low
     * enough that the loop stays trivial.
     */
    private static final int MAX_RULES = 100;

    private final CategoryRuleRepository rules;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;

    public CategoryRuleService(CategoryRuleRepository rules,
            CategoryService categoryService,
            CurrentUserService currentUser) {
        this.rules = rules;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<CategoryRuleDTO> getAll() {
        return rules.findByUser_IdOrderByPriorityAscIdAsc(currentUser.requireId())
                .stream()
                .map(CategoryRuleMapping::toDTO)
                .toList();
    }

    /** The rules an import should apply, in the order it should try them. */
    @Transactional(readOnly = true)
    public List<CategoryRule> activeRulesFor(Integer userId) {
        return rules.findByUser_IdAndActiveTrueOrderByPriorityAscIdAsc(userId);
    }

    @Transactional
    public CategoryRuleDTO create(CategoryRuleDTO dto) {
        User user = currentUser.require();

        if (rules.countByUser_Id(user.getId()) >= MAX_RULES) {
            throw new ConflictException(
                    "You already have " + MAX_RULES + " rules, which is the limit. "
                            + "Delete one you no longer need.");
        }

        Category category = categoryService.requireOwned(dto.getCategoryId());

        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        apply(rule, dto, category);
        // Appended rather than inserted: a new rule must not silently take
        // precedence over one the user has already ordered deliberately.
        rule.setPriority(nextPriority(user.getId()));

        return CategoryRuleMapping.toDTO(rules.save(rule));
    }

    @Transactional
    public CategoryRuleDTO update(Integer id, CategoryRuleDTO dto) {
        CategoryRule rule = requireOwned(id);
        Category category = categoryService.requireOwned(dto.getCategoryId());

        apply(rule, dto, category);
        rule.setPriority(dto.getPriority());

        return CategoryRuleMapping.toDTO(rules.save(rule));
    }

    /**
     * Moves a rule one place up or down the order.
     *
     * <p>Swapping with the neighbour rather than renumbering the whole list: the
     * user's mental model is "this one before that one", and a full renumber
     * writes every row to express a change to two of them.
     */
    @Transactional
    public List<CategoryRuleDTO> move(Integer id, boolean up) {
        Integer userId = currentUser.requireId();
        List<CategoryRule> ordered = rules.findByUser_IdOrderByPriorityAscIdAsc(userId);

        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new NotFoundException("Rule not found");
        }

        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= ordered.size()) {
            // Already at the end it was asked to move towards. Not an error.
            return ordered.stream().map(CategoryRuleMapping::toDTO).toList();
        }

        CategoryRule moved = ordered.get(index);
        ordered.set(index, ordered.get(target));
        ordered.set(target, moved);

        // Priorities are rewritten from the new order rather than swapped, so a
        // list that has drifted into duplicate or gapped values comes out clean.
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPriority(i);
        }
        rules.saveAll(ordered);

        return ordered.stream().map(CategoryRuleMapping::toDTO).toList();
    }

    @Transactional
    public void delete(Integer id) {
        rules.delete(requireOwned(id));
    }

    /** Removes the rules pointing at a category that is being deleted. */
    @Transactional
    public void deleteForCategory(Integer userId, Integer categoryId) {
        rules.deleteAll(rules.findByUser_IdAndCategory_Id(userId, categoryId));
    }

    private void apply(CategoryRule rule, CategoryRuleDTO dto, Category category) {
        rule.setCategory(category);
        rule.setPattern(dto.getPattern().trim());
        rule.setMatchType(dto.getMatchType());
        rule.setActive(dto.isActive());
    }

    private int nextPriority(Integer userId) {
        return rules.findByUser_IdOrderByPriorityAscIdAsc(userId)
                .stream()
                .mapToInt(CategoryRule::getPriority)
                .max()
                .orElse(-1) + 1;
    }

    /**
     * Loads a rule belonging to the current user.
     *
     * <p>A rule owned by someone else reports "not found" rather than
     * "forbidden", so ids cannot be probed for existence.
     */
    private CategoryRule requireOwned(Integer id) {
        return rules.findByIdAndUser_Id(id, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Rule not found"));
    }
}
