import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { CategoryRuleService } from '../core/services/category-rule.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import {
  CategoryRule,
  MatchType,
  MatchTypeOption,
} from '../core/models/category-rule.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';

/**
 * Filing rules for imported transactions.
 *
 * Presented as one ordered list rather than a table you can sort, because the
 * order *is* the rule set: the first rule that matches wins, and a view that
 * let you reorder it visually without changing it would be lying.
 */
@Component({
  selector: 'app-category-rules',
  standalone: true,
  imports: [ReactiveFormsModule, ModalComponent],
  templateUrl: './category-rules.html',
  styleUrl: './category-rules.css',
})
export class CategoryRulesComponent {
  private readonly ruleService = inject(CategoryRuleService);
  private readonly categoryService = inject(CategoryService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly categories = this.categoryService.categories;
  protected readonly colorById = this.categoryService.colorById;

  protected readonly rules = signal<CategoryRule[]>([]);
  protected readonly matchTypes = signal<MatchTypeOption[]>([]);

  protected readonly isLoading = signal(true);
  protected readonly isFormOpen = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly pendingDelete = signal<CategoryRule | null>(null);
  protected readonly movingId = signal<number | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    pattern: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    matchType: ['CONTAINS' as MatchType, [Validators.required]],
    categoryId: ['', [Validators.required]],
    active: [true],
  });

  constructor() {
    this.categoryService.load().subscribe({
      error: (err) => this.notifications.showError(describeError(err, 'Could not load categories.')),
    });

    // The wording for each match type comes from the server, so the two cannot
    // drift apart when a type is added or renamed.
    this.ruleService.matchTypes().subscribe({
      next: (types) => this.matchTypes.set(types),
      error: () => this.matchTypes.set([]),
    });

    this.load();
  }

  protected load(): void {
    this.isLoading.set(true);
    this.ruleService.load().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load rules.'));
      },
    });
  }

  // ------------------------------------------------------------------ editing

  protected openCreate(): void {
    this.editingId.set(null);
    this.form.reset({
      pattern: '',
      matchType: 'CONTAINS',
      categoryId: String(this.categories()[0]?.id ?? ''),
      active: true,
    });
    this.isFormOpen.set(true);
  }

  protected openEdit(rule: CategoryRule): void {
    this.editingId.set(rule.id);
    this.form.reset({
      pattern: rule.pattern,
      matchType: rule.matchType,
      categoryId: String(rule.categoryId),
      active: rule.active,
    });
    this.isFormOpen.set(true);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const payload = {
      pattern: raw.pattern.trim(),
      matchType: raw.matchType,
      categoryId: Number(raw.categoryId),
      active: raw.active,
    };

    this.isSaving.set(true);
    const id = this.editingId();
    const request = id === null
      ? this.ruleService.create(payload)
      : this.ruleService.update(id, payload);

    request.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isFormOpen.set(false);
        this.notifications.showSuccess(id === null ? 'Rule added.' : 'Rule updated.');
        this.load();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not save that rule.'));
      },
    });
  }

  protected confirmDelete(): void {
    const rule = this.pendingDelete();
    if (!rule) {
      return;
    }

    this.ruleService.delete(rule.id).subscribe({
      next: () => {
        this.pendingDelete.set(null);
        this.notifications.showSuccess('Rule deleted.');
        this.load();
      },
      error: (err) => {
        this.pendingDelete.set(null);
        this.notifications.showError(describeError(err, 'Could not delete that rule.'));
      },
    });
  }

  // ----------------------------------------------------------------- ordering

  protected move(rule: CategoryRule, direction: 'up' | 'down'): void {
    this.movingId.set(rule.id);

    const request = direction === 'up'
      ? this.ruleService.moveUp(rule.id)
      : this.ruleService.moveDown(rule.id);

    request.subscribe({
      // The server sends back the whole list, because moving one rule renumbers
      // the others.
      next: (reordered) => {
        this.rules.set(reordered);
        this.movingId.set(null);
      },
      error: (err) => {
        this.movingId.set(null);
        this.notifications.showError(describeError(err, 'Could not reorder the rules.'));
      },
    });
  }

  // ------------------------------------------------------------------ display

  protected categoryColor(categoryId: number): string {
    return this.colorById().get(categoryId) ?? 'var(--text-tertiary)';
  }

  protected matchLabel(type: MatchType): string {
    return this.matchTypes().find((option) => option.value === type)?.label ?? type;
  }

  protected isFirst(rule: CategoryRule): boolean {
    return this.rules()[0]?.id === rule.id;
  }

  protected isLast(rule: CategoryRule): boolean {
    return this.rules()[this.rules().length - 1]?.id === rule.id;
  }
}
