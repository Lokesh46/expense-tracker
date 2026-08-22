import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged } from 'rxjs';

import { AdminService } from '../../core/services/admin.service';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';
import {
  AccountStatus,
  AdminStats,
  Role,
  ROLE_LABELS,
  STATUS_LABELS,
  UserSummary,
} from '../../core/models/user.models';
import { ModalComponent } from '../../shared/modal/modal';
import { describeError } from '../../core/utils/api-error';

type SortKey = 'username' | 'createdAt' | 'lastLoginAt' | 'loginCount' | 'role';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ModalComponent],
  templateUrl: './users.html',
  styleUrl: './users.css',
})
export class AdminUsersComponent {
  private readonly admin = inject(AdminService);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  protected readonly roleLabels = ROLE_LABELS;
  protected readonly statusLabels = STATUS_LABELS;

  /** Used to mark your own row, since most actions on it are refused. */
  protected readonly me = this.auth.getUsername();

  // --- results ---
  protected readonly rows = signal<UserSummary[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(20);
  protected readonly isLoading = signal(false);
  protected readonly stats = signal<AdminStats | null>(null);

  // --- sorting ---
  protected readonly sortBy = signal<SortKey>('createdAt');
  protected readonly sortDir = signal<'asc' | 'desc'>('desc');

  // --- creating ---
  protected readonly isFormOpen = signal(false);
  protected readonly isSaving = signal(false);

  protected readonly filters = this.fb.nonNullable.group({
    search: [''],
    role: [''],
    status: [''],
  });

  protected readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(30)]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    email: ['', [Validators.email]],
    role: ['MEMBER' as Role, [Validators.required]],
  });

  protected readonly hasFilters = computed(() =>
    Object.values(this.filters.getRawValue()).some((value) => value !== '')
  );

  protected readonly rangeLabel = computed(() => {
    const total = this.totalElements();
    if (total === 0) {
      return 'No accounts';
    }
    const start = this.page() * this.size() + 1;
    const end = Math.min(start + this.rows().length - 1, total);
    return `${start}–${end} of ${total}`;
  });

  constructor() {
    this.loadStats();

    // Typing re-queries the server, so wait for a pause and ignore repeats.
    this.filters.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(deepEqual), takeUntilDestroyed())
      .subscribe(() => {
        this.page.set(0);
        this.load();
      });

    effect(() => {
      // Read so the effect re-runs when any of them change.
      this.page();
      this.size();
      this.sortBy();
      this.sortDir();
      this.load();
    });
  }

  protected load(): void {
    const filters = this.filters.getRawValue();
    this.isLoading.set(true);

    this.admin
      .searchUsers({
        page: this.page(),
        size: this.size(),
        sortBy: this.sortBy(),
        sortDir: this.sortDir(),
        search: filters.search || null,
        role: (filters.role as Role) || null,
        status: (filters.status as AccountStatus) || null,
      })
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.totalElements.set(result.totalElements);
          this.totalPages.set(result.totalPages);
          this.isLoading.set(false);
        },
        error: (err) => {
          this.isLoading.set(false);
          this.notifications.showError(describeError(err, 'Could not load accounts.'));
        },
      });
  }

  private loadStats(): void {
    this.admin.getStats().subscribe({
      next: (stats) => this.stats.set(stats),
      error: () => {
        // The list is the point of this screen; a missing summary strip is not
        // worth a second error message on top of the one the list would raise.
      },
    });
  }

  // ------------------------------------------------------------------ sorting

  protected sort(key: SortKey): void {
    if (this.sortBy() === key) {
      this.sortDir.update((dir) => (dir === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortBy.set(key);
      // Names read best A-Z; dates and counts read best newest or largest first.
      this.sortDir.set(key === 'username' || key === 'role' ? 'asc' : 'desc');
    }
    this.page.set(0);
  }

  protected sortMark(key: SortKey): string {
    if (this.sortBy() !== key) {
      return '';
    }
    return this.sortDir() === 'asc' ? '↑' : '↓';
  }

  // ------------------------------------------------------------------- paging

  protected goToPage(page: number): void {
    if (page >= 0 && page < Math.max(this.totalPages(), 1)) {
      this.page.set(page);
    }
  }

  protected changeSize(value: string): void {
    this.size.set(Number(value));
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.filters.reset({ search: '', role: '', status: '' });
  }

  // ----------------------------------------------------------------- creating

  protected openCreate(): void {
    this.form.reset({ username: '', password: '', email: '', role: 'MEMBER' });
    this.isFormOpen.set(true);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    this.isSaving.set(true);

    this.admin
      .createUser({
        username: value.username.trim(),
        password: value.password,
        email: value.email.trim() || undefined,
        role: value.role,
      })
      .subscribe({
        next: (created) => {
          this.isSaving.set(false);
          this.isFormOpen.set(false);
          this.notifications.showSuccess(`${created.username} can now sign in.`);
          this.loadStats();
          this.load();
        },
        error: (err) => {
          this.isSaving.set(false);
          this.notifications.showError(describeError(err, 'Could not create that account.'));
        },
      });
  }

  protected invalid(field: 'username' | 'password' | 'email'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.dirty || control.touched);
  }

  // ---------------------------------------------------------------- formatting

  /**
   * "3 days ago", or the fallback when there is no timestamp.
   *
   * The fallback is a parameter because null means different things in different
   * columns: an account that has never signed in, versus one created before the
   * application recorded a join date. "Never joined" would be nonsense.
   */
  protected relative(iso: string | null, fallback = 'Never'): string {
    if (!iso) {
      return fallback;
    }

    const then = new Date(iso).getTime();
    const seconds = Math.round((Date.now() - then) / 1000);

    if (seconds < 60) {
      return 'Just now';
    }
    if (seconds < 3600) {
      return plural(Math.floor(seconds / 60), 'minute');
    }
    if (seconds < 86_400) {
      return plural(Math.floor(seconds / 3600), 'hour');
    }
    if (seconds < 2_592_000) {
      return plural(Math.floor(seconds / 86_400), 'day');
    }
    return new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' });
  }

  protected exact(iso: string | null, fallback = 'Never'): string {
    return iso
      ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
      : fallback;
  }
}

function plural(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'} ago`;
}

/**
 * The filter form emits a new object on every change, so identity comparison
 * never suppresses anything. Values are flat strings, which is why this is
 * enough.
 */
function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
