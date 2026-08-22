package com.lokesh_codes.expense_tracker_backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.CategoryDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.CategoryMapping;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.BudgetRepository;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

@Service
public class CategoryService {

    /** Given to every new account so the app is usable immediately. */
    private static final Map<String, String> DEFAULT_CATEGORIES = new java.util.LinkedHashMap<>() {
        {
            put("Groceries", "#22c55e");
            put("Rent & Bills", "#6366f1");
            put("Transport", "#0ea5e9");
            put("Eating Out", "#f97316");
            put("Shopping", "#ec4899");
            put("Health", "#14b8a6");
            put("Entertainment", "#a855f7");
            put("Other", "#64748b");
        }
    };

    private static final String DEFAULT_COLOR = "#6366f1";

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CurrentUserService currentUser;

    public CategoryService(CategoryRepository categoryRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            CurrentUserService currentUser) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<CategoryDTO> getAllCategories() {
        return categoryRepository.findByUser_IdOrderByNameAsc(currentUser.requireId())
                .stream()
                .map(CategoryMapping::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryDTO getCategoryById(Integer id) {
        return CategoryMapping.toDTO(requireOwned(id));
    }

    @Transactional
    public CategoryDTO createCategory(CategoryDTO dto) {
        User user = currentUser.require();
        String name = dto.getName().trim();

        if (categoryRepository.existsByUser_IdAndNameIgnoreCase(user.getId(), name)) {
            throw new ConflictException("You already have a category called \"" + name + "\".");
        }

        Category category = new Category(user, name,
                dto.getColor() == null ? DEFAULT_COLOR : dto.getColor());
        return CategoryMapping.toDTO(categoryRepository.save(category));
    }

    @Transactional
    public CategoryDTO updateCategory(Integer id, CategoryDTO dto) {
        Category category = requireOwned(id);
        String name = dto.getName().trim();

        // A rename that collides with a different category of the same user is a
        // conflict; renaming a category to its own current name is not.
        categoryRepository.findByUser_IdAndNameIgnoreCase(category.getUser().getId(), name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("You already have a category called \"" + name + "\".");
                });

        category.setName(name);
        if (dto.getColor() != null) {
            category.setColor(dto.getColor());
        }
        return CategoryMapping.toDTO(categoryRepository.save(category));
    }

    @Transactional
    public void deleteCategory(Integer id) {
        Category category = requireOwned(id);

        // Transactions require a category, so deleting one in use would either
        // orphan rows or fail on a constraint deep in the persistence layer.
        // Refusing up front gives the user something they can act on.
        long inUse = transactionRepository.count((root, query, cb) -> cb.and(
                cb.equal(root.get("user").get("id"), category.getUser().getId()),
                cb.equal(root.get("category").get("id"), id)));

        if (inUse > 0) {
            throw new ConflictException(
                    "\"" + category.getName() + "\" is used by " + inUse
                            + (inUse == 1 ? " transaction" : " transactions")
                            + ". Reassign or delete them first.");
        }

        budgetRepository.findByUser_IdAndCategory_Id(category.getUser().getId(), id)
                .ifPresent(budgetRepository::delete);

        categoryRepository.delete(category);
    }

    /**
     * Creates the starter categories for a newly registered user. Idempotent, so
     * it is safe if registration is ever retried.
     */
    @Transactional
    public void seedDefaultsFor(User user) {
        DEFAULT_CATEGORIES.forEach((name, color) -> {
            if (!categoryRepository.existsByUser_IdAndNameIgnoreCase(user.getId(), name)) {
                categoryRepository.save(new Category(user, name, color));
            }
        });
    }

    /**
     * Loads a category belonging to the current user.
     *
     * <p>A category owned by someone else reports "not found" rather than
     * "forbidden", so ids cannot be probed for existence.
     */
    Category requireOwned(Integer id) {
        return categoryRepository.findByIdAndUser_Id(id, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Category not found"));
    }
}
