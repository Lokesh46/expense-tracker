import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { CATEGORY_COLORS, Category } from '../core/models/category.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [ReactiveFormsModule, ModalComponent],
  templateUrl: './categories.html',
  styleUrl: './categories.css',
})
export class CategoriesComponent {
  private readonly categoryService = inject(CategoryService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly palette = CATEGORY_COLORS;
  protected readonly categories = this.categoryService.categories;

  protected readonly isLoading = signal(true);
  protected readonly isFormOpen = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly pendingDelete = signal<Category | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(50)]],
    color: [CATEGORY_COLORS[0] as string, [Validators.required]],
  });

  constructor() {
    this.categoryService.load().subscribe({
      next: () => this.isLoading.set(false),
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load categories.'));
      },
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    // Offer the first colour not already taken, so a new category is visually
    // distinct without the user having to think about it.
    const used = new Set(this.categories().map((c) => c.color?.toLowerCase()));
    const free = this.palette.find((color) => !used.has(color.toLowerCase()));
    this.form.reset({ name: '', color: free ?? this.palette[0] });
    this.isFormOpen.set(true);
  }

  protected openEdit(category: Category): void {
    this.editingId.set(category.id);
    this.form.reset({ name: category.name, color: category.color ?? this.palette[0] });
    this.isFormOpen.set(true);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const payload = this.form.getRawValue();
    payload.name = payload.name.trim();

    this.isSaving.set(true);
    const id = this.editingId();
    const request = id
      ? this.categoryService.update(id, payload)
      : this.categoryService.create(payload);

    request.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isFormOpen.set(false);
        this.notifications.showSuccess(id ? 'Category updated.' : 'Category added.');
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not save the category.'));
      },
    });
  }

  protected confirmDelete(): void {
    const target = this.pendingDelete();
    if (!target) {
      return;
    }

    this.categoryService.delete(target.id).subscribe({
      next: () => {
        this.pendingDelete.set(null);
        this.notifications.showSuccess(`"${target.name}" deleted.`);
      },
      error: (err) => {
        this.pendingDelete.set(null);
        // A category still in use returns a 409 explaining how many
        // transactions reference it, which is more useful than a generic error.
        this.notifications.showError(describeError(err, 'Could not delete the category.'));
      },
    });
  }

  protected invalid(field: 'name' | 'color'): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }
}
