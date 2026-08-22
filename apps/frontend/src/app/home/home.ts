import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TransactionService } from '../core/services/transaction.service';
import { CategoryService } from '../core/services/category.service';
import { Transaction } from '../core/models/transaction.models';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home {
  protected readonly greeting = signal('Welcome back!');
  private readonly txService = inject(TransactionService);
  private readonly catService = inject(CategoryService);

  protected readonly transactions = signal<Transaction[]>([]);
  protected readonly categories = signal([] as any[]);

  constructor() {
    // Load latest data for home summaries
    this.txService.getTransactions().subscribe({});
    this.catService.getCategories().subscribe({});

    this.txService.transactions$.subscribe((t) => this.transactions.set(t || []));
    this.catService.categories$.subscribe((c) => this.categories.set(c || []));
  }
  protected readonly quickActions = signal([
    {
      title: 'Manage Categories',
      description: 'Create and organize your expense categories for better tracking.',
      cta: 'Go to Categories',
      link: '/categories',
    },
    {
      title: 'Log a new expense',
      description: 'Capture spending instantly and categorize it on the spot.',
      cta: 'Add expense',
      link: '/transactions',
    },
    {
      title: 'View Transactions',
      description: 'Review all your transactions with detailed filters and search.',
      cta: 'View Transactions',
      link: '/transactions',
    },
  ]);

  protected readonly plannerTips = signal([
    'Schedule a weekly check-in to review trends and plan for next week.',
    'Create category caps so essentials and lifestyle spending stay balanced.',
    'Turn on reminders in your profile to never miss a bill payment.',
    'Organize transactions by category to better understand your spending habits.',
  ]);

  protected readonly totalThisMonth = computed(() => {
    const now = new Date();
    const ts = this.transactions();
    return ts
      .filter((t) => {
        const d = new Date(t.date);
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
      })
      .reduce((s, t) => s + (t.amount || 0), 0);
  });

  protected readonly transactionsThisMonth = computed(() => {
    const now = new Date();
    return this.transactions().filter((t) => {
      const d = new Date(t.date);
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
    }).length;
  });

  protected readonly topCategoryThisMonth = computed(() => {
    const now = new Date();
    const groups: Record<string, number> = {};
    for (const t of this.transactions()) {
      const d = new Date(t.date);
      if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()) {
        groups[t.categoryId] = (groups[t.categoryId] || 0) + (t.amount || 0);
      }
    }
    const entries = Object.entries(groups).sort((a, b) => b[1] - a[1]);
    if (entries.length === 0) return null;
    const [catId, amt] = entries[0];
    const cat = this.categories().find((c: any) => {
      const cid = c?.id ?? c?._id ?? '';
      return String(cid) === String(catId);
    });
    return { id: catId, name: cat ? cat.name : 'Unknown', total: amt };
  });

  protected readonly recentTransactions = computed(() =>
    [...this.transactions()].sort((a, b) => +new Date(b.date) - +new Date(a.date)).slice(0, 4)
  );
}

