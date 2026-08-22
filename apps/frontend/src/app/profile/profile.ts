import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AccountService } from '../core/services/account.service';
import { AuthService } from '../core/services/auth.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { RecurringService } from '../core/services/recurring.service';
import { TransactionService } from '../core/services/transaction.service';
import { ThemeService, Theme } from '../core/services/theme.service';
import { ActivityEntry, ROLE_LABELS } from '../core/models/user.models';
import { ModalComponent } from '../shared/modal/modal';
import { describeError } from '../core/utils/api-error';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ModalComponent],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly transactionService = inject(TransactionService);
  private readonly recurringService = inject(RecurringService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  protected readonly categoryService = inject(CategoryService);
  protected readonly themeService = inject(ThemeService);
  protected readonly accountService = inject(AccountService);

  protected readonly roleLabels = ROLE_LABELS;

  protected readonly transactionCount = signal<number | null>(null);
  protected readonly recurringCount = signal<number | null>(null);

  protected readonly activity = signal<ActivityEntry[]>([]);
  protected readonly isPasswordOpen = signal(false);
  protected readonly isEmailOpen = signal(false);
  protected readonly isSaving = signal(false);

  /** The loaded profile, or null until it arrives. */
  protected readonly account = this.accountService.profile;

  protected readonly username = this.auth.getUsername() ?? 'Unknown';
  protected readonly initial = this.username.charAt(0).toUpperCase();

  protected readonly passwordForm = this.fb.nonNullable.group({
    currentPassword: ['', [Validators.required]],
    newPassword: ['', [Validators.required, Validators.minLength(8)]],
  });

  protected readonly emailForm = this.fb.nonNullable.group({
    email: ['', [Validators.email]],
  });

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

    this.accountService.load().subscribe({
      error: (err) => this.notifications.showError(describeError(err, 'Could not load your account.')),
    });

    // Ten is enough to spot a sign-in you do not recognise, which is what this
    // list is for; the full history is not something anyone scrolls.
    this.accountService.myActivity(0, 10).subscribe({
      next: (page) => this.activity.set(page.content),
      error: () => {
        // A missing history is not worth an error on top of whatever caused it.
      },
    });
  }

  // ------------------------------------------------------------------ account

  protected openEmail(): void {
    this.emailForm.reset({ email: this.account()?.email ?? '' });
    this.isEmailOpen.set(true);
  }

  protected saveEmail(): void {
    if (this.emailForm.invalid) {
      this.emailForm.markAllAsTouched();
      return;
    }

    this.isSaving.set(true);
    this.accountService.updateEmail(this.emailForm.getRawValue().email.trim()).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isEmailOpen.set(false);
        this.notifications.showSuccess('Email updated.');
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not update your email.'));
      },
    });
  }

  /**
   * Changing a password ends every session, this one included, so the user is
   * signed out and sent to the sign-in screen rather than left holding a token the
   * next request would reject.
   */
  protected changePassword(): void {
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      return;
    }

    const { currentPassword, newPassword } = this.passwordForm.getRawValue();
    this.isSaving.set(true);

    this.accountService.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.isPasswordOpen.set(false);
        this.passwordForm.reset({ currentPassword: '', newPassword: '' });
        this.auth.logout();
        this.notifications.showSuccess('Password changed. Please sign in again.');
        this.router.navigate(['/login']);
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notifications.showError(describeError(err, 'Could not change your password.'));
      },
    });
  }

  protected invalidPassword(field: 'currentPassword' | 'newPassword'): boolean {
    const control = this.passwordForm.controls[field];
    return control.invalid && (control.dirty || control.touched);
  }

  protected when(iso: string | null, fallback = 'Never'): string {
    return iso
      ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
      : fallback;
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
