package com.lokesh_codes.expense_tracker_backend.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryDTO {

    private Integer id;

    @NotBlank(message = "Category name is required")
    @Size(min = 2, max = 50, message = "Category name must be between 2 and 50 characters")
    private String name;

    @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$", message = "Colour must be a hex value such as #4f46e5")
    private String color;
}
