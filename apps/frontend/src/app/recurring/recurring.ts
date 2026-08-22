import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { RecurringService } from '../core/services/recurring.service';
import { CURRENCIES, PAYMENT_METHODS } from '../core/models/transaction.models';
import { FREQUENCIES, Frequency, RecurringTransaction } from '../core/models/recurring.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';
import { formatMoney } from '../core/utils/money';

@Component({
  selector: 'app-recurring',
  standalone: true,
  imports: [ReactiveFormsModule, ModalComponent],
  templateUrl: './recurring.html',
  styleUrl: './recurring.css',
})
export class RecurringComponent {
  private readonly recurringService = inject(RecurringService);
  private readonly categoryService = inject(CategoryService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly frequencies = FREQUENCIES;
  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly currencies = CURRENCIES;
  protected readonly categories = this.categoryService.categories;
  protected readonly colorById = this.categoryService.colorById;

  protected readonly rules = signal<RecurringTransaction[]>([]);
  protected readonly isLoading = signal(true);
  protected readonly isRunning = signal(false);

  protected readonly isFormOpen = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly pendingDelete = signal<RecurringTransaction | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    description: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.min(0.01)]],
    currency: ['GBP', [Validators.required]],
    categoryId: ['', [Validators.required]],
    frequency: ['MONTHLY' as Frequency, [Validators.required]],
    nextRunDate: [new Date().toISOString().slice(0, 10), [Validators.required]],
    endDate: [''],
    paymentMethod: ['Bank Transfer', [Validators.required]],
    comments: ['', [Validators.maxLength(500)]],
    active: [true],
  });

  protected readonly activeCount = computed(() => this.rules().filter((r) => r.active).length);

  /** Rules whose next run has already passed, so a transaction is owed. */
  protected readonly dueCount = computed(() => {
    const today = new Date().toISOString().slice(0, 10);
    return this.rules().filter((r) => r.active && r.nextRunDate <= today).length;
  });

  constructor() {
    this.categoryService.load().subscribe();
    this.load();
  }

  protected load(): void {
    this.isLoading.set(true);
    this.recurringService.load().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load recurring rules.'));
      },
    });
  }

  protected runNow(): void {
    this.isRunning.set(true);
    this.recurringService.runNow().subscribe({
      next: ({ created }) => {
        this.isRunning.set(false);
        if (created > 0) {
          this.notifications.showSuccess(
            `Created ${created} transaction${created === 1 ? '' : 's'}.`
          );
        } else {
          this.notifications.showInfo('Nothing is due right now.');
        }
        this.load();
      },
      error: (err) => {
        this.isRunning.set(false);
        this.notifications.showError(describeError(err, 'Could not run the rules.'));
      },
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.form.reset({
      description: '',
      amount: '',
      currency: 'GBP',
      categoryId: String(this.categories()[0]?.id ?? ''),
      frequency: 'MONTHLY',
      nextRunDate: new Date().toISOString().slice(0, 10),
      endDate: '',
      paymentMethod: 'Bank Transfer',
      comments: '',
      active: true,
    });
    this.isFormOpen.set(true);
  }

  protected openEdit(rule: RecurringTransaction): void {
    this.editingId.set(rule.id);
    this.form.reset({
      description: rule.description,
      amount: String(rule.amount),
      currency: rule.currency,
      categoryId: String(rule.categoryId),
      frequency: rule.frequency,
      nextRunDate: rule.nextRunDate,
      endDate: rule.endDate ?? '',
      paymentMethod: rule.paymentMethod,
      comments: rule.comments ?? '',
      active: rule.active,
    });
    this.isFormOpen.set(true);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const payload = {
      description: value.description.trim(),
      amount: Number(value.amount),
      currency: value.currency,
      categoryId: Number(value.categoryId),
      frequency: value.frequency,
      nextRunDate: value.nextRunDate,
      endDate: value.endDate || null,
      paymentMethod: value.paymentMethod,
      comments: value.comments?.trim() || undefined,
      active: value.active,
    };

    this.isSaving.set(true);
    const id = this.editingId();
    const request = id
      ? this.recurringService.update(id, payload)
      : this.recurringService.create(payload);

    request.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isFormOpen.set(false);
        this.notifications.showSuccess(id ? 'Rule updated.' : 'Rule created.');
        this.load();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not save the rule.'));
      },
    });
  }

  protected togglePaused(rule: RecurringTransaction): void {
    this.recurringService
      .update(rule.id, {
        categoryId: rule.categoryId,
        description: rule.description,
        amount: rule.amount,
        currency: rule.currency,
        paymentMethod: rule.paymentMethod,
        comments: rule.comments,
        frequency: rule.frequency,
        nextRunDate: rule.nextRunDate,
        endDate: rule.endDate ?? null,
        active: !rule.active,
      })
      .subscribe({
        next: () => {
          this.notifications.showSuccess(rule.active ? 'Rule paused.' : 'Rule resumed.');
          this.load();
        },
        error: (err) => this.notifications.showError(describeError(err, 'Could not update.')),
      });
  }

  protected confirmDelete(): void {
    const target = this.pendingDelete();
    if (!target) {
      return;
    }

    this.recurringService.delete(target.id).subscribe({
      next: () => {
        this.pendingDelete.set(null);
        this.notifications.showSuccess('Rule deleted.');
        this.load();
      },
      error: (err) => {
        this.pendingDelete.set(null);
        this.notifications.showError(describeError(err, 'Could not delete the rule.'));
      },
    });
  }

  // --------------------------------------------------------------- display

  protected money(rule: RecurringTransaction): string {
    return formatMoney(rule.amount, rule.currency);
  }

  protected frequencyLabel(frequency: Frequency): string {
    return this.frequencies.find((f) => f.value === frequency)?.label ?? frequency;
  }

  protected isDue(rule: RecurringTransaction): boolean {
    return rule.active && rule.nextRunDate <= new Date().toISOString().slice(0, 10);
  }

  protected categoryColor(id: number): string {
    return this.colorById().get(id) ?? 'var(--accent)';
  }

  protected invalid(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }
}
