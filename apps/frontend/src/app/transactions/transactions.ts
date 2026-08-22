import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TransactionService } from '../core/services/transaction.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { Transaction, CreateTransactionRequest } from '../core/models/transaction.models';
import { Category } from '../core/models/category.models';
 

@Component({
  selector: 'app-transactions',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './transactions.html',
  styleUrl: './transactions.css',
})
export class TransactionsComponent implements OnInit {
  private readonly transactionService = inject(TransactionService);
  private readonly categoryService = inject(CategoryService);
  private readonly notificationService = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  transactions = signal<Transaction[]>([]);
  // sort options: newest (default), oldest, amount_desc, amount_asc
  sortBy = signal<'newest' | 'oldest' | 'amount_desc' | 'amount_asc'>('newest');
  categories = signal<Category[]>([]);
  isLoading = signal(false);
  isCategoriesLoading = signal(false);
  isFormLoading = signal(false);
  showCreateForm = signal(false);
  editingTransactionId = signal<string | null>(null);
  transactionForm: FormGroup;
  filterForm: FormGroup;

  // Pagination
  currentPage = signal(1);
  itemsPerPage = signal(10);

  constructor() {
    this.transactionForm = this.fb.group({
      amount: ['', [Validators.required, Validators.min(0.01)]],
      categoryId: ['', Validators.required],
      description: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(200)]],
      currency: ['USD', Validators.required],
      date: ['', Validators.required],
      paymentMethod: ['', Validators.required],
      comments: ['', Validators.maxLength(500)],
    });
    this.filterForm = this.fb.group({
      categoryId: [''],
      fromDate: [''],
      toDate: [''],
      minAmount: [''],
      maxAmount: [''],
      paymentMethod: [''],
      search: [''],
    });

  }

  ngOnInit(): void {
    this.loadCategories();
    this.loadTransactions();
    // subscribe to service stream so the list updates live (create/update/delete)
    this.transactionService.transactions$.subscribe((t) => {
      if (t) this.transactions.set(t);
    });
  }

  loadCategories(): void {
    this.isCategoriesLoading.set(true);
    this.categoryService.getCategories().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.isCategoriesLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to load categories', err);
        this.notificationService.showError('Failed to load categories');
        this.isCategoriesLoading.set(false);
      },
    });
  }

  loadTransactions(): void {
    this.isLoading.set(true);
    this.transactionService.getTransactions().subscribe({
      next: (transactions) => {
        this.transactions.set(transactions);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to load transactions', err);
        this.notificationService.showError('Failed to load transactions');
        this.isLoading.set(false);
      },
    });
  }

  // Filter helpers
  onFilterApply(): void {
    this.currentPage.set(1);
  }

  onFilterClear(): void {
    this.filterForm.reset({ categoryId: '', fromDate: '', toDate: '', minAmount: '', maxAmount: '', paymentMethod: '', search: '' });
    this.currentPage.set(1);
  }

  onCreateSubmit(): void {
    if (this.transactionForm.invalid) {
      this.notificationService.showWarning('Please fill in all required fields');
      return;
    }

    this.isFormLoading.set(true);
    const formValue = this.transactionForm.value;
    const payload: CreateTransactionRequest = {
      ...formValue,
      amount: Number.parseFloat(formValue.amount),
    };

    this.transactionService.createTransaction(payload).subscribe({
      next: () => {
        this.notificationService.showSuccess('Transaction created successfully');
        this.transactionForm.reset();
        this.transactionForm.patchValue({ currency: 'USD' });
        this.showCreateForm.set(false);
        this.isFormLoading.set(false);
        // show the newest transactions (newly created should appear first)
        this.sortBy.set('newest');
        this.currentPage.set(1);
      },
      error: (err) => {
        console.error('Failed to create transaction', err);
        this.notificationService.showError('Failed to create transaction');
        this.isFormLoading.set(false);
      },
    });
  }

  onSortChange(value: string): void {
    if (value === 'newest' || value === 'oldest' || value === 'amount_desc' || value === 'amount_asc') {
      this.sortBy.set(value as any);
      this.currentPage.set(1);
    }
  }

  onEditClick(transaction: Transaction): void {
    this.editingTransactionId.set(transaction.id);
    this.transactionForm.patchValue({
      amount: transaction.amount,
      categoryId: transaction.categoryId,
      description: transaction.description,
      currency: transaction.currency,
      date: transaction.date,
      paymentMethod: transaction.paymentMethod,
      comments: transaction.comments || '',
    });
    this.showCreateForm.set(true);
  }

  onUpdateSubmit(): void {
    const transactionId = this.editingTransactionId();
    if (!transactionId || this.transactionForm.invalid) {
      this.notificationService.showWarning('Please fill in all required fields');
      return;
    }

    this.isFormLoading.set(true);
    const formValue = this.transactionForm.value;
    const payload = {
      ...formValue,
      amount: Number.parseFloat(formValue.amount),
    };

    this.transactionService.updateTransaction(transactionId, payload).subscribe({
      next: () => {
        this.notificationService.showSuccess('Transaction updated successfully');
        this.transactionForm.reset();
        this.showCreateForm.set(false);
        this.editingTransactionId.set(null);
        this.isFormLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to update transaction', err);
        this.notificationService.showError('Failed to update transaction');
        this.isFormLoading.set(false);
      },
    });
  }

  onDeleteClick(id: string): void {
    if (confirm('Are you sure you want to delete this transaction?')) {
      this.transactionService.deleteTransaction(id).subscribe({
        next: () => {
          this.notificationService.showSuccess('Transaction deleted successfully');
        },
        error: (err) => {
          console.error('Failed to delete transaction', err);
          this.notificationService.showError('Failed to delete transaction');
        },
      });
    }
  }

  onCancel(): void {
    this.transactionForm.reset();
    this.transactionForm.patchValue({ currency: 'USD' });
    this.showCreateForm.set(false);
    this.editingTransactionId.set(null);
  }

  getCategoryName(categoryId: string): string {
    const category = this.categories().find((c) => c.id === categoryId);
    return category?.name || 'Unknown';
  }

  get isEditMode(): boolean {
    return this.editingTransactionId() !== null;
  }

  get filteredTransactions(): Transaction[] {
    const all = this.transactions();
    const f = this.filterForm.value;

    const filtered = all.filter((t) => {
      // Category filter
      if (f.categoryId && f.categoryId !== '' && t.categoryId !== f.categoryId) return false;

      // Payment method
      if (f.paymentMethod && f.paymentMethod !== '' && t.paymentMethod !== f.paymentMethod) return false;

      // Description search
      if (f.search && f.search.trim() !== '') {
        const q = f.search.trim().toLowerCase();
        if (!t.description.toLowerCase().includes(q) && !(t.comments || '').toLowerCase().includes(q)) return false;
      }

      // Amount range
      if (f.minAmount !== null && f.minAmount !== '' && !Number.isNaN(Number.parseFloat(f.minAmount))) {
        if (t.amount < Number.parseFloat(f.minAmount)) return false;
      }
      if (f.maxAmount !== null && f.maxAmount !== '' && !Number.isNaN(Number.parseFloat(f.maxAmount))) {
        if (t.amount > Number.parseFloat(f.maxAmount)) return false;
      }

      // Date range
      if (f.fromDate) {
        const from = new Date(f.fromDate);
        const td = new Date(t.date);
        if (td < from) return false;
      }
      if (f.toDate) {
        const to = new Date(f.toDate);
        const td = new Date(t.date);
        // include whole day
        to.setHours(23, 59, 59, 999);
        if (td > to) return false;
      }

      return true;
    });

    // Apply sorting
    const sort = this.sortBy();
    filtered.sort((a, b) => {
      if (sort === 'newest') {
        return +new Date(b.date) - +new Date(a.date);
      }
      if (sort === 'oldest') {
        return +new Date(a.date) - +new Date(b.date);
      }
      if (sort === 'amount_desc') {
        return (b.amount || 0) - (a.amount || 0);
      }
      if (sort === 'amount_asc') {
        return (a.amount || 0) - (b.amount || 0);
      }
      return 0;
    });

    return filtered;
  }


  get paginatedTransactions(): Transaction[] {
    const items = this.filteredTransactions;
    const startIndex = (this.currentPage() - 1) * this.itemsPerPage();
    const endIndex = startIndex + this.itemsPerPage();
    return items.slice(startIndex, endIndex);
  }

  get totalPages(): number {
    return Math.ceil(this.filteredTransactions.length / this.itemsPerPage());
  }

  goToPage(page: number): void {
    if (page > 0 && page <= this.totalPages) {
      this.currentPage.set(page);
    }
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages) {
      this.currentPage.set(this.currentPage() + 1);
    }
  }

  previousPage(): void {
    if (this.currentPage() > 1) {
      this.currentPage.set(this.currentPage() - 1);
    }
  }
}
