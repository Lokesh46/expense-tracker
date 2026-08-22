import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { BudgetService } from '../core/services/budget.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { TransactionService } from '../core/services/transaction.service';
import { Budget } from '../core/models/budget.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';
import { dominantCurrency, formatMoney } from '../core/utils/money';

@Component({
  selector: 'app-budgets',
  standalone: true,
  imports: [ReactiveFormsModule, ModalComponent],
  templateUrl: './budgets.html',
  styleUrl: './budgets.css',
})
export class BudgetsComponent {
  private readonly budgetService = inject(BudgetService);
  private readonly categoryService = inject(CategoryService);
  private readonly transactionService = inject(TransactionService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly categories = this.categoryService.categories;
  protected readonly colorById = this.categoryService.colorById;

  protected readonly budgets = signal<Budget[]>([]);
  protected readonly isLoading = signal(true);
  protected readonly month = signal(new Date().toISOString().slice(0, 7));

  /**
   * Budget limits are a single figure, so they need one currency to display in.
   * It is inferred from what the user actually spends rather than assumed.
   */
  protected readonly currency = signal('GBP');

  protected readonly isFormOpen = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly editing = signal<Budget | null>(null);
  protected readonly pendingDelete = signal<Budget | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    categoryId: ['', [Validators.required]],
    monthlyLimit: ['', [Validators.required, Validators.min(0.01)]],
  });

  /** Categories that do not already have a budget. */
  protected readonly availableCategories = computed(() => {
    const taken = new Set(this.budgets().map((b) => b.categoryId));
    const editingId = this.editing()?.categoryId;
    return this.categories().filter((c) => !taken.has(c.id) || c.id === editingId);
  });

  protected readonly summary = computed(() => {
    const items = this.budgets();
    return {
      count: items.length,
      allocated: items.reduce((sum, b) => sum + b.monthlyLimit, 0),
      spent: items.reduce((sum, b) => sum + b.spent, 0),
      overCount: items.filter((b) => b.exceeded).length,
    };
  });

  protected readonly monthLabel = computed(() =>
    new Date(`${this.month()}-01T00:00:00`).toLocaleDateString(undefined, {
      month: 'long',
      year: 'numeric',
    })
  );

  constructor() {
    this.categoryService.load().subscribe();

    // Sample recent spending to work out which currency to present in.
    this.transactionService.search({ size: 100, sortBy: 'date', sortDir: 'desc' }).subscribe({
      next: (page) => this.currency.set(dominantCurrency(page.content)),
      error: () => {
        /* Keep the default; this is only a display nicety. */
      },
    });

    effect(() => {
      const month = this.month();
      this.isLoading.set(true);

      this.budgetService.load(month).subscribe({
        next: (budgets) => {
          this.budgets.set(budgets);
          this.isLoading.set(false);
        },
        error: (err) => {
          this.isLoading.set(false);
          this.notifications.showError(describeError(err, 'Could not load budgets.'));
        },
      });
    });
  }

  protected shiftMonth(delta: number): void {
    const [year, month] = this.month().split('-').map(Number);
    // Day 1 avoids the end-of-month clamping that day-31 arithmetic causes.
    const date = new Date(year, month - 1 + delta, 1);
    this.month.set(
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
    );
  }

  protected openCreate(): void {
    this.editing.set(null);
    this.form.reset({
      categoryId: String(this.availableCategories()[0]?.id ?? ''),
      monthlyLimit: '',
    });
    this.form.controls.categoryId.enable();
    this.isFormOpen.set(true);
  }

  protected openEdit(budget: Budget): void {
    this.editing.set(budget);
    this.form.reset({
      categoryId: String(budget.categoryId),
      monthlyLimit: String(budget.monthlyLimit),
    });
    // Only the limit can be changed; moving a budget to another category would
    // be a different budget, and the API ignores the field on update.
    this.form.controls.categoryId.disable();
    this.isFormOpen.set(true);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const payload = {
      categoryId: Number(value.categoryId),
      monthlyLimit: Number(value.monthlyLimit),
    };

    this.isSaving.set(true);
    const existing = this.editing();
    const request = existing
      ? this.budgetService.update(existing.id, payload)
      : this.budgetService.create(payload);

    request.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isFormOpen.set(false);
        this.notifications.showSuccess(existing ? 'Budget updated.' : 'Budget set.');
        this.reload();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not save the budget.'));
      },
    });
  }

  protected confirmDelete(): void {
    const target = this.pendingDelete();
    if (!target) {
      return;
    }

    this.budgetService.delete(target.id).subscribe({
      next: () => {
        this.pendingDelete.set(null);
        this.notifications.showSuccess('Budget removed.');
        this.reload();
      },
      error: (err) => {
        this.pendingDelete.set(null);
        this.notifications.showError(describeError(err, 'Could not remove the budget.'));
      },
    });
  }

  private reload(): void {
    this.budgetService.load(this.month()).subscribe({
      next: (budgets) => this.budgets.set(budgets),
    });
  }

  // --------------------------------------------------------------- display

  protected money(amount: number): string {
    return formatMoney(amount, this.currency());
  }

  /** Meter width, capped so an overspent bar cannot overflow its track. */
  protected meterWidth(budget: Budget): string {
    return `${Math.min(budget.percentUsed, 100)}%`;
  }

  protected meterState(budget: Budget): string {
    if (budget.exceeded) {
      return 'is-over';
    }
    return budget.percentUsed >= 80 ? '' : 'is-safe';
  }

  protected statusLabel(budget: Budget): string {
    if (budget.exceeded) {
      return `${this.money(budget.spent - budget.monthlyLimit)} over`;
    }
    return `${this.money(budget.remaining)} left`;
  }

  protected categoryColor(id: number): string {
    return this.colorById().get(id) ?? 'var(--accent)';
  }

  protected invalid(field: 'categoryId' | 'monthlyLimit'): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }
}
