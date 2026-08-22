package com.lokesh_codes.expense_tracker_backend.repository;

import com.lokesh_codes.expense_tracker_backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Integer> {
}
