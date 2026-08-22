import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged } from 'rxjs';

import { AdminService } from '../../core/services/admin.service';
import { NotificationService } from '../../core/services/notification.service';
import {
  ACTIVITY_ACTIONS,
  ActivityAction,
  ActivityEntry,
} from '../../core/models/user.models';
import { describeError } from '../../core/utils/api-error';

/**
 * The audit trail.
 *
 * Sign-ins, failures, lockouts and account changes across every account. Not what
 * anybody spent: ordinary use is never logged, so this screen cannot become a way
 * around the privacy the rest of the design maintains.
 */
@Component({
  selector: 'app-admin-activity',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './activity.html',
  styleUrl: './activity.css',
})
export class AdminActivityComponent {
  private readonly admin = inject(AdminService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly actions = ACTIVITY_ACTIONS;

  protected readonly rows = signal<ActivityEntry[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(25);
  protected readonly isLoading = signal(false);

  protected readonly filters = this.fb.nonNullable.group({
    username: [''],
    action: [''],
    from: [''],
    to: [''],
    adverseOnly: [false],
  });

  protected readonly hasFilters = computed(() => {
    const value = this.filters.getRawValue();
    return value.username !== '' || value.action !== '' || value.from !== '' || value.to !== ''
      || value.adverseOnly;
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
    this.filters.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(deepEqual), takeUntilDestroyed())
      .subscribe(() => {
        this.page.set(0);
        this.load();
      });

    effect(() => {
      this.page();
      this.size();
      this.load();
    });
  }

  protected load(): void {
    this.isLoading.set(true);

    this.admin.searchActivity({ ...this.query(), page: this.page(), size: this.size() }).subscribe({
      next: (result) => {
        this.rows.set(result.content);
        this.totalElements.set(result.totalElements);
        this.totalPages.set(result.totalPages);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load the activity log.'));
      },
    });
  }

  protected exportCsv(): void {
    this.admin.exportActivityCsv(this.query()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `activity-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        URL.revokeObjectURL(url);
        this.notifications.showSuccess('Export downloaded.');
      },
      error: (err) => this.notifications.showError(describeError(err, 'Could not export.')),
    });
  }

  private query() {
    const value = this.filters.getRawValue();
    return {
      username: value.username || null,
      action: (value.action as ActivityAction) || null,
      from: value.from || null,
      to: value.to || null,
      adverseOnly: value.adverseOnly || null,
    };
  }

  protected clearFilters(): void {
    this.filters.reset({ username: '', action: '', from: '', to: '', adverseOnly: false });
  }

  protected goToPage(page: number): void {
    if (page >= 0 && page < Math.max(this.totalPages(), 1)) {
      this.page.set(page);
    }
  }

  protected changeSize(value: string): void {
    this.size.set(Number(value));
    this.page.set(0);
  }

  protected exact(iso: string): string {
    return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  }

  /**
   * Turns a user-agent string into something a person can read.
   *
   * Deliberately crude. The full string is kept in a tooltip; what the column
   * needs to answer is "was this the same kind of device as usual", and eighty
   * characters of version numbers answers it worse than one word.
   */
  protected client(userAgent: string | null): string {
    if (!userAgent) {
      return '—';
    }
    const checks: ReadonlyArray<[RegExp, string]> = [
      [/edg\//i, 'Edge'],
      [/opr\/|opera/i, 'Opera'],
      // Chrome must come after the browsers that also claim to be Chrome.
      [/chrome|crios/i, 'Chrome'],
      [/firefox|fxios/i, 'Firefox'],
      [/safari/i, 'Safari'],
      [/curl/i, 'curl'],
      [/postman/i, 'Postman'],
    ];

    for (const [pattern, name] of checks) {
      if (pattern.test(userAgent)) {
        return name;
      }
    }
    return 'Other';
  }
}

function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
