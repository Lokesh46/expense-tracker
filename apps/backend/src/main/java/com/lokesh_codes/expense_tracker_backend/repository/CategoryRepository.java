package com.lokesh_codes.expense_tracker_backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lokesh_codes.expense_tracker_backend.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Integer> {

    List<Category> findByUser_IdOrderByNameAsc(Integer userId);

    long countByUser_Id(Integer userId);

    void deleteByUser_Id(Integer userId);

    Optional<Category> findByIdAndUser_Id(Integer id, Integer userId);

    boolean existsByUser_IdAndNameIgnoreCase(Integer userId, String name);

    Optional<Category> findByUser_IdAndNameIgnoreCase(Integer userId, String name);
}
