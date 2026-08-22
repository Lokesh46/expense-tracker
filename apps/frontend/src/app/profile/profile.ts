import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../core/services/auth.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { RecurringService } from '../core/services/recurring.service';
import { TransactionService } from '../core/services/transaction.service';
import { ThemeService, Theme } from '../core/services/theme.service';
import { describeError } from '../core/utils/api-error';

@Component({
  selector: 'app-profile',
  standalone: true,
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly transactionService = inject(TransactionService);
  private readonly recurringService = inject(RecurringService);
  private readonly notifications = inject(NotificationService);
  protected readonly categoryService = inject(CategoryService);
  protected readonly themeService = inject(ThemeService);

  protected readonly transactionCount = signal<number | null>(null);
  protected readonly recurringCount = signal<number | null>(null);

  protected readonly username = this.auth.getUsername() ?? 'Unknown';
  protected readonly initial = this.username.charAt(0).toUpperCase();

  protected readonly expiry = computed(() => {
    const date = this.auth.getExpiry();
    return date
      ? date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
      : 'Unknown';
  });

  constructor() {
    this.categoryService.load().subscribe();

    // Only the totals are needed, so ask for the smallest page the API allows
    // and read the count from the envelope rather than downloading the rows.
    this.transactionService.search({ size: 1 }).subscribe({
      next: (page) => this.transactionCount.set(page.totalElements),
      error: (err) => this.notifications.showError(describeError(err, 'Could not load totals.')),
    });

    this.recurringService.load().subscribe({
      next: (rules) => this.recurringCount.set(rules.length),
    });
  }

  protected setTheme(theme: Theme): void {
    this.themeService.set(theme);
  }

  protected exportEverything(): void {
    this.transactionService.exportCsv({}).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `ledger-export-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        URL.revokeObjectURL(url);
        this.notifications.showSuccess('Export downloaded.');
      },
      error: (err) => this.notifications.showError(describeError(err, 'Could not export.')),
    });
  }

  protected signOut(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
