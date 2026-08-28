import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { TransactionService } from '../core/services/transaction.service';
import {
  CURRENCIES,
  PAYMENT_METHODS,
  DateOrder,
  Transaction,
  TransactionQuery,
  TransactionType,
} from '../core/models/transaction.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';
import {
  expensesOnly,
  formatMoney,
  incomeOnly,
  totalsByCurrency,
} from '../core/utils/money';

type SortKey = 'date' | 'amount' | 'description';

@Component({
  selector: 'app-transactions',
  standalone: true,
  imports: [ReactiveFormsModule, ModalComponent],
  templateUrl: './transactions.html',
  styleUrl: './transactions.css',
})
export class TransactionsComponent {
  private readonly transactionService = inject(TransactionService);
  private readonly categoryService = inject(CategoryService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly currencies = CURRENCIES;
  protected readonly categories = this.categoryService.categories;
  protected readonly colorById = this.categoryService.colorById;

  // --- results ---
  protected readonly rows = signal<Transaction[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(20);
  protected readonly isLoading = signal(false);

  // --- sorting ---
  protected readonly sortBy = signal<SortKey>('date');
  protected readonly sortDir = signal<'asc' | 'desc'>('desc');

  // --- editing ---
  protected readonly isFormOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly isSaving = signal(false);
  protected readonly pendingDelete = signal<Transaction | null>(null);

  // --- import ---
  protected readonly isImportOpen = signal(false);
  protected readonly importErrors = signal<string[]>([]);
  protected readonly isImporting = signal(false);

  /**
   * How many rows the last import flagged as looking like duplicates.
   *
   * Kept so the banner can say what happened. Flagged rows are real rows and
   * are already counted in every total on the page, which is the part a user
   * has to be told — otherwise their spending appears to have jumped and
   * nothing on screen explains why.
   */
  protected readonly importFlagged = signal(0);

  /** How the last import understood the file's columns, shown back to the user. */
  protected readonly importMapping = signal('');

  /**
   * How to read an ambiguous slash-separated date in the next upload.
   *
   * Asked rather than guessed: 03/04/2026 parses either way, so a wrong guess
   * is not an error the user can see — it is every ambiguous date in the file
   * landing in the wrong month at once.
   */
  protected readonly dateOrder = signal<DateOrder>('DAY_FIRST');

  /**
   * The currency to assume for a file that names none, which is most of them —
   * a bank has no reason to repeat it on every row of its own statement.
   *
   * Seeded from what the account already uses, so the common case needs no
   * thought.
   */
  protected readonly importCurrency = signal<string>('GBP');

  // --- duplicate review ---
  protected readonly showOnlyDuplicates = signal(false);
  protected readonly clearingDuplicate = signal<number | null>(null);

  protected readonly filters = this.fb.nonNullable.group({
    search: [''],
    categoryId: [''],
    from: [''],
    to: [''],
    minAmount: [''],
    maxAmount: [''],
    paymentMethod: [''],
  });

  protected readonly form = this.fb.nonNullable.group({
    description: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.min(0.01)]],
    // Always positive; `type` is what says which way the money went.
    type: ['EXPENSE' as TransactionType, [Validators.required]],
    currency: ['GBP', [Validators.required]],
    categoryId: ['', [Validators.required]],
    date: [new Date().toISOString().slice(0, 10), [Validators.required]],
    paymentMethod: ['Card', [Validators.required]],
    comments: ['', [Validators.maxLength(500)]],
  });

  /**
   * Page totals, split by currency.
   *
   * Amounts in different currencies are never added together: a single number
   * mixing GBP and INR would be meaningless.
   */
  protected readonly pageTotals = computed(() => totalsByCurrency(expensesOnly(this.rows())));

  /**
   * Income on this page, shown separately.
   *
   * Folded into the total above it would read as spending — amounts are stored
   * positive whichever way the money went, so a salary row would inflate the
   * figure rather than offset it.
   */
  protected readonly pageIncome = computed(() => totalsByCurrency(incomeOnly(this.rows())));

  protected readonly hasFilters = computed(() => {
    const value = this.filters.getRawValue();
    return Object.values(value).some((v) => v !== '' && v !== null);
  });

  protected readonly rangeLabel = computed(() => {
    const total = this.totalElements();
    if (total === 0) {
      return 'No entries';
    }
    const start = this.page() * this.size() + 1;
    const end = Math.min(start + this.rows().length - 1, total);
    return `${start}–${end} of ${total}`;
  });

  constructor() {
    this.categoryService.load().subscribe({
      error: (err) => this.notifications.showError(describeError(err, 'Could not load categories.')),
    });

    // Typing in the filter bar re-queries the server, so wait for a pause and
    // ignore repeats — otherwise every keystroke is a round trip.
    this.filters.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(deepEqual), takeUntilDestroyed())
      .subscribe(() => {
        this.page.set(0);
        this.load();
      });

    // Re-run whenever paging or sorting changes.
    effect(() => {
      this.page();
      this.size();
      this.sortBy();
      this.sortDir();
      this.load();
    });
  }

  // ------------------------------------------------------------------ loading

  protected load(): void {
    this.isLoading.set(true);

    this.transactionService.search(this.currentQuery()).subscribe({
      next: (result) => {
        this.rows.set(result.content);
        this.totalElements.set(result.totalElements);
        this.totalPages.set(result.totalPages);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load transactions.'));
      },
    });
  }

  private currentQuery(): TransactionQuery {
    const f = this.filters.getRawValue();
    return {
      page: this.page(),
      size: this.size(),
      sortBy: this.sortBy(),
      sortDir: this.sortDir(),
      search: f.search || null,
      categoryId: f.categoryId ? Number(f.categoryId) : null,
      from: f.from || null,
      to: f.to || null,
      minAmount: f.minAmount ? Number(f.minAmount) : null,
      maxAmount: f.maxAmount ? Number(f.maxAmount) : null,
      paymentMethod: f.paymentMethod || null,
      // Undefined rather than false when the toggle is off: false would ask the
      // server for rows that are explicitly not flagged, which is a filter, not
      // the absence of one.
      possibleDuplicate: this.showOnlyDuplicates() ? true : undefined,
    };
  }

  // -------------------------------------------------------- duplicate review

  /**
   * Opens the import dialog, defaulting the currency to what the account
   * already uses.
   *
   * Read from the rows on screen rather than stored: it is a starting point the
   * user can change per file, not a setting.
   */
  protected openImport(): void {
    this.importCurrency.set(this.rows()[0]?.currency ?? 'GBP');
    this.importErrors.set([]);
    this.importFlagged.set(0);
    this.importMapping.set('');
    this.isImportOpen.set(true);
  }

  protected toggleDuplicateFilter(): void {
    this.showOnlyDuplicates.update((on) => !on);
    this.page.set(0);
    this.load();
  }

  /** Closes the import dialog and shows only what it flagged. */
  protected reviewDuplicates(): void {
    this.isImportOpen.set(false);
    this.showOnlyDuplicates.set(true);
    this.page.set(0);
    this.load();
  }

  /** Confirms a flagged row is genuine, so the badge stops following it around. */
  protected keepAsGenuine(transaction: Transaction): void {
    this.clearingDuplicate.set(transaction.id);

    this.transactionService.markNotDuplicate(transaction.id).subscribe({
      next: () => {
        this.clearingDuplicate.set(null);
        this.notifications.showSuccess('Kept. It will not be flagged again.');
        this.load();
      },
      error: (err) => {
        this.clearingDuplicate.set(null);
        this.notifications.showError(describeError(err, 'Could not update that transaction.'));
      },
    });
  }

  // ----------------------------------------------------------------- sorting

  protected toggleSort(key: SortKey): void {
    if (this.sortBy() === key) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortBy.set(key);
      this.sortDir.set(key === 'description' ? 'asc' : 'desc');
    }
    this.page.set(0);
  }

  protected sortIndicator(key: SortKey): string {
    if (this.sortBy() !== key) {
      return '';
    }
    return this.sortDir() === 'asc' ? '↑' : '↓';
  }

  // ----------------------------------------------------------------- paging

  protected goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.page.set(page);
    }
  }

  protected changeSize(value: string): void {
    this.size.set(Number(value));
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.filters.reset({
      search: '',
      categoryId: '',
      from: '',
      to: '',
      minAmount: '',
      maxAmount: '',
      paymentMethod: '',
    });
  }

  // ------------------------------------------------------------------ create

  protected openCreate(): void {
    this.editingId.set(null);
    this.form.reset({
      description: '',
      amount: '',
      type: 'EXPENSE',
      currency: this.rows()[0]?.currency ?? 'GBP',
      categoryId: String(this.categories()[0]?.id ?? ''),
      date: new Date().toISOString().slice(0, 10),
      paymentMethod: 'Card',
      comments: '',
    });
    this.isFormOpen.set(true);
  }

  protected openEdit(transaction: Transaction): void {
    this.editingId.set(transaction.id);
    this.form.reset({
      description: transaction.description,
      amount: String(transaction.amount),
      type: transaction.type ?? 'EXPENSE',
      currency: transaction.currency,
      categoryId: String(transaction.categoryId),
      date: transaction.date,
      paymentMethod: transaction.paymentMethod,
      comments: transaction.comments ?? '',
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
      type: value.type,
      currency: value.currency,
      categoryId: Number(value.categoryId),
      date: value.date,
      paymentMethod: value.paymentMethod,
      comments: value.comments?.trim() || undefined,
    };

    this.isSaving.set(true);
    const id = this.editingId();
    const request = id
      ? this.transactionService.update(id, payload)
      : this.transactionService.create(payload);

    request.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isFormOpen.set(false);
        this.notifications.showSuccess(id ? 'Transaction updated.' : 'Transaction recorded.');
        this.load();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not save the transaction.'));
      },
    });
  }

  // ------------------------------------------------------------------ delete

  protected confirmDelete(): void {
    const target = this.pendingDelete();
    if (!target) {
      return;
    }

    this.transactionService.delete(target.id).subscribe({
      next: () => {
        this.pendingDelete.set(null);
        this.notifications.showSuccess('Transaction deleted.');

        // Deleting the only row on the last page would otherwise leave the user
        // staring at an empty table.
        if (this.rows().length === 1 && this.page() > 0) {
          this.page.set(this.page() - 1);
        } else {
          this.load();
        }
      },
      error: (err) => {
        this.pendingDelete.set(null);
        this.notifications.showError(describeError(err, 'Could not delete the transaction.'));
      },
    });
  }

  // ------------------------------------------------------------------ csv

  protected exportCsv(): void {
    this.transactionService.exportCsv(this.currentQuery()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `transactions-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        URL.revokeObjectURL(url);
        this.notifications.showSuccess('Export downloaded.');
      },
      error: (err) => this.notifications.showError(describeError(err, 'Could not export.')),
    });
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    this.isImporting.set(true);
    this.importErrors.set([]);
    this.importFlagged.set(0);
    this.importMapping.set('');

    this.transactionService
      .importCsv(file, this.dateOrder(), this.importCurrency())
      .subscribe({
        next: (result) => {
          this.isImporting.set(false);
          this.importErrors.set(result.errors);
          this.importFlagged.set(result.flagged);
          this.importMapping.set(result.columnMapping ?? '');

          if (result.imported > 0) {
            this.notifications.showSuccess(
              `Imported ${result.imported} transaction${result.imported === 1 ? '' : 's'}.`
            );
            this.categoryService.load().subscribe();
            this.load();
          }
          if (result.skipped > 0 && result.imported === 0) {
            this.notifications.showWarning('Nothing could be imported from that file.');
          }

          // The dialog stays open whenever there is something to read: rows
          // skipped, duplicates flagged, or a column mapping worth checking
          // before the results are trusted. Closing it would leave the totals
          // changed with no explanation on screen.
          if (result.errors.length === 0 && result.flagged === 0) {
            this.isImportOpen.set(false);
          }
          // Allow the same file to be chosen again after a fix.
          input.value = '';
        },
        error: (err) => {
          this.isImporting.set(false);
          input.value = '';
          this.notifications.showError(describeError(err, 'Could not read that file.'));
        },
      });
  }

  // ------------------------------------------------------------------ display

  protected money(transaction: Transaction): string {
    return formatMoney(transaction.amount, transaction.currency);
  }

  protected formatTotal(total: { currency: string; total: number }): string {
    return formatMoney(total.total, total.currency);
  }

  protected categoryColor(id: number): string {
    return this.colorById().get(id) ?? 'var(--accent)';
  }

  protected invalid(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }
}

/** Value comparison for the filter form, which only ever holds primitives. */
function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
