import { Component, computed, inject, signal } from '@angular/core';

import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { ReviewService } from '../core/services/review.service';
import { MerchantGroup } from '../core/models/review.models';
import { describeError } from '../core/utils/api-error';
import { formatMoney } from '../core/utils/money';

/**
 * The queue of imported rows whose category was guessed.
 *
 * Grouped by merchant, and that is the whole design. A statement of two hundred
 * rows is six or seven shops; presented as rows it is two hundred decisions and
 * nobody finishes, presented as merchants it is seven and takes a minute.
 *
 * Approving is the common case, so it is one click and never asks anything
 * further. Refiling asks one question — whether to remember it — because a
 * correction you have to repeat every month is not a correction.
 */
@Component({
  selector: 'app-review',
  standalone: true,
  templateUrl: './review.html',
  styleUrl: './review.css',
})
export class ReviewComponent {
  private readonly reviewService = inject(ReviewService);
  private readonly categoryService = inject(CategoryService);
  private readonly notifications = inject(NotificationService);

  protected readonly categories = this.categoryService.categories;
  protected readonly colorById = this.categoryService.colorById;

  protected readonly merchants = signal<MerchantGroup[]>([]);
  protected readonly merchantsTotal = signal(0);
  protected readonly transactionsTotal = signal(0);
  protected readonly isLoading = signal(true);

  /** The merchant an action is running for, so only its own buttons go quiet. */
  protected readonly busy = signal<string | null>(null);
  protected readonly isApprovingAll = signal(false);

  /** Which group has its category picker open, and what has been chosen in it. */
  protected readonly editing = signal<string | null>(null);
  protected readonly chosenCategoryId = signal('');
  protected readonly rememberChoice = signal(true);

  /** Which group has its example descriptions showing. */
  protected readonly expanded = signal<string | null>(null);

  /**
   * How many groups were left out of the listing.
   *
   * Shown rather than left to be inferred. A capped list that looks complete
   * would tell somebody they had finished when they had not.
   */
  protected readonly hidden = computed(() =>
    Math.max(0, this.merchantsTotal() - this.merchants().length)
  );

  constructor() {
    this.categoryService.load().subscribe({
      error: (err) =>
        this.notifications.showError(describeError(err, 'Could not load categories.')),
    });
    this.load();
  }

  protected load(): void {
    this.isLoading.set(true);
    this.reviewService.merchants().subscribe({
      next: (queue) => {
        this.merchants.set(queue.merchants);
        this.merchantsTotal.set(queue.merchantsTotal);
        this.transactionsTotal.set(queue.transactions);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load the review queue.'));
      },
    });
  }

  /**
   * What to call a group on screen.
   *
   * A description made only of a channel prefix and a reference number has no
   * merchant in it, and those rows are collected together. Saying so beats an
   * empty label, and beats inventing a name the statement never gave.
   */
  protected displayName(group: MerchantGroup): string {
    return group.merchantName || 'No merchant name';
  }

  /** Whether a decision about this group can be turned into a filing rule. */
  protected canRemember(group: MerchantGroup): boolean {
    // A rule matches on text. With no merchant name there is nothing to match,
    // so the option is hidden rather than offered and quietly ignored.
    return !!group.merchantName;
  }

  protected categoryColor(id: number | null): string {
    return (id === null ? undefined : this.colorById().get(id)) ?? 'var(--accent)';
  }

  protected totalsOf(group: MerchantGroup): string {
    // Joined, never added: a single figure mixing currencies would be worse
    // than showing two.
    return group.totals
      .map((total) => formatMoney(total.amount, total.currency))
      .join(' · ');
  }

  /** Wording for how the suggestion was reached, or null when there is none. */
  protected reasonFor(group: MerchantGroup): string | null {
    switch (group.source) {
      case 'HISTORY':
        return 'where you have filed it before';
      case 'FILE':
        return 'named in the file';
      default:
        return null;
    }
  }

  protected toggleSamples(group: MerchantGroup): void {
    this.expanded.update((open) => (open === group.merchantHash ? null : group.merchantHash));
  }

  // ------------------------------------------------------------- approving

  protected approve(group: MerchantGroup): void {
    this.busy.set(group.merchantHash);
    this.reviewService.approve(group.merchantHash).subscribe({
      next: (result) => {
        this.busy.set(null);
        this.notifications.showSuccess(result.message);
        this.remove(group.merchantHash, group.transactionCount);
      },
      error: (err) => {
        this.busy.set(null);
        this.notifications.showError(describeError(err, 'Could not approve those.'));
      },
    });
  }

  protected approveAll(): void {
    this.isApprovingAll.set(true);
    this.reviewService.approveAll().subscribe({
      next: (result) => {
        this.isApprovingAll.set(false);
        this.notifications.showSuccess(result.message);
        this.load();
      },
      error: (err) => {
        this.isApprovingAll.set(false);
        this.notifications.showError(describeError(err, 'Could not approve those.'));
      },
    });
  }

  // -------------------------------------------------------------- refiling

  protected startEditing(group: MerchantGroup): void {
    this.editing.set(group.merchantHash);
    this.chosenCategoryId.set(group.suggestedCategoryId ? String(group.suggestedCategoryId) : '');
    // Defaulted on: the merchant is about to be filed somewhere it was not
    // filed automatically, which is exactly the case worth remembering.
    this.rememberChoice.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(null);
    this.chosenCategoryId.set('');
  }

  protected onCategoryChosen(event: Event): void {
    this.chosenCategoryId.set((event.target as HTMLSelectElement).value);
  }

  protected onRememberChanged(event: Event): void {
    this.rememberChoice.set((event.target as HTMLInputElement).checked);
  }

  protected saveCategory(group: MerchantGroup): void {
    const categoryId = Number(this.chosenCategoryId());
    if (!categoryId) {
      return;
    }

    this.busy.set(group.merchantHash);
    this.reviewService
      .assign(group.merchantHash, { categoryId, createRule: this.rememberChoice() })
      .subscribe({
        next: (result) => {
          this.busy.set(null);
          this.cancelEditing();
          this.notifications.showSuccess(result.message);
          this.remove(group.merchantHash, group.transactionCount);
        },
        error: (err) => {
          this.busy.set(null);
          this.notifications.showError(describeError(err, 'Could not move those.'));
        },
      });
  }

  /**
   * Drops a settled group from the list without refetching.
   *
   * The server has already told us how many rows moved, and reloading would
   * make every approval cost a round trip and shuffle the list under the
   * cursor mid-review.
   */
  private remove(merchantHash: string, transactionCount: number): void {
    this.merchants.update((groups) =>
      groups.filter((group) => group.merchantHash !== merchantHash)
    );
    this.merchantsTotal.update((total) => Math.max(0, total - 1));
    this.transactionsTotal.update((total) => Math.max(0, total - transactionCount));
    // The badge is shared, so it has to be told too.
    this.reviewService.refreshCount().subscribe({ error: () => undefined });
  }
}
