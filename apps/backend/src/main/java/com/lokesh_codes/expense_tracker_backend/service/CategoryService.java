package com.lokesh_codes.expense_tracker_backend.service;

import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.DTO.CategoryDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.CategoryMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    // Create a new category
    public CategoryDTO createCategory(CategoryDTO categoryDTO) {
        // Convert DTO to Entity
        Category category = CategoryMapping.toEntity(categoryDTO);

        // Save the category to the database
        Category savedCategory = categoryRepository.save(category);

        // Convert the saved entity back to DTO
        return CategoryMapping.toDTO(savedCategory);
    }

    public List<CategoryDTO> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(CategoryMapping::toDTO)
                .collect(Collectors.toList());
    }

    public CategoryDTO getCategoryById(Integer id) {
        var category = categoryRepository.findById(id).orElseThrow(() -> new RuntimeException("Category not found"));
        return CategoryMapping.toDTO(category);
    }

    public CategoryDTO updateCategory(Integer id, CategoryDTO categoryDTO) {
        var existing = categoryRepository.findById(id).orElseThrow(() -> new RuntimeException("Category not found"));
        existing.setName(categoryDTO.getName());
        var saved = categoryRepository.save(existing);
        return CategoryMapping.toDTO(saved);
    }

    public void deleteCategory(Integer id) {
        var existing = categoryRepository.findById(id).orElseThrow(() -> new RuntimeException("Category not found"));
        categoryRepository.delete(existing);
    }
}
