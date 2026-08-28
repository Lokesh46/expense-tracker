package com.lokesh_codes.expense_tracker_backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lokesh_codes.expense_tracker_backend.entity.CategoryRule;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Integer> {

    List<CategoryRule> findByUser_IdOrderByPriorityAscIdAsc(Integer userId);

    /** The rules an import evaluates, already in the order it must try them. */
    List<CategoryRule> findByUser_IdAndActiveTrueOrderByPriorityAscIdAsc(Integer userId);

    Optional<CategoryRule> findByIdAndUser_Id(Integer id, Integer userId);

    long countByUser_Id(Integer userId);

    void deleteByUser_Id(Integer userId);

    /** Rules pointing at a category, so deleting one does not leave a rule dangling. */
    List<CategoryRule> findByUser_IdAndCategory_Id(Integer userId, Integer categoryId);
}
