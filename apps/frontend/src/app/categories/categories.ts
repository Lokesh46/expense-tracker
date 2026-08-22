import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { Category, CreateCategoryRequest } from '../core/models/category.models';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './categories.html',
  styleUrl: './categories.css',
})
export class CategoriesComponent implements OnInit {
  private readonly categoryService = inject(CategoryService);
  private readonly notificationService = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  categories = signal<Category[]>([]);
  isLoading = signal(false);
  isFormLoading = signal(false);
  showCreateForm = signal(false);
  editingCategoryId = signal<string | null>(null);
  categoryForm: FormGroup;

  constructor() {
    this.categoryForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(50)]],
    });
  }

  ngOnInit(): void {
    this.loadCategories();
  }

  loadCategories(): void {
    this.isLoading.set(true);
    this.categoryService.getCategories().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to load categories', err);
        this.notificationService.showError('Failed to load categories');
        this.isLoading.set(false);
      },
    });
  }

  onCreateSubmit(): void {
    if (this.categoryForm.invalid) {
      this.notificationService.showWarning('Please fill in all required fields');
      return;
    }

    this.isFormLoading.set(true);
    const payload: CreateCategoryRequest = this.categoryForm.value;

    this.categoryService.createCategory(payload).subscribe({
      next: () => {
        this.notificationService.showSuccess('Category created successfully');
        this.categoryForm.reset();
        this.showCreateForm.set(false);
        this.isFormLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to create category', err);
        this.notificationService.showError('Failed to create category');
        this.isFormLoading.set(false);
      },
    });
  }

  onEditClick(category: Category): void {
    this.editingCategoryId.set(category.id);
    this.categoryForm.patchValue({ name: category.name });
    this.showCreateForm.set(true);
  }

  onUpdateSubmit(): void {
    const categoryId = this.editingCategoryId();
    if (!categoryId || this.categoryForm.invalid) {
      this.notificationService.showWarning('Please fill in all required fields');
      return;
    }

    this.isFormLoading.set(true);
    const payload = this.categoryForm.value;

    this.categoryService.updateCategory(categoryId, payload).subscribe({
      next: () => {
        this.notificationService.showSuccess('Category updated successfully');
        this.categoryForm.reset();
        this.showCreateForm.set(false);
        this.editingCategoryId.set(null);
        this.isFormLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to update category', err);
        this.notificationService.showError('Failed to update category');
        this.isFormLoading.set(false);
      },
    });
  }

  onDeleteClick(id: string): void {
    if (confirm('Are you sure you want to delete this category?')) {
      this.categoryService.deleteCategory(id).subscribe({
        next: () => {
          this.notificationService.showSuccess('Category deleted successfully');
        },
        error: (err) => {
          console.error('Failed to delete category', err);
          this.notificationService.showError('Failed to delete category');
        },
      });
    }
  }

  onCancel(): void {
    this.categoryForm.reset();
    this.showCreateForm.set(false);
    this.editingCategoryId.set(null);
  }

  get isEditMode(): boolean {
    return this.editingCategoryId() !== null;
  }
}
