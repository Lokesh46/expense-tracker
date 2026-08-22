import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { AdminService } from '../../core/services/admin.service';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';
import {
  Role,
  ROLE_LABELS,
  STATUS_LABELS,
  UserDetail,
} from '../../core/models/user.models';
import { ModalComponent } from '../../shared/modal/modal';
import { describeError } from '../../core/utils/api-error';

/**
 * One account, and everything that can be done to it.
 *
 * The powerful actions live here rather than in the list, so suspending or
 * deleting somebody takes a deliberate navigation and never a misclick in a row
 * of twenty.
 */
@Component({
  selector: 'app-admin-user-detail',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ModalComponent],
  templateUrl: './user-detail.html',
  styleUrl: './user-detail.css',
})
export class AdminUserDetailComponent {
  private readonly admin = inject(AdminService);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly roleLabels = ROLE_LABELS;
  protected readonly statusLabels = STATUS_LABELS;

  /**
   * Read from the route once. This screen is reached by navigation rather than by
   * changing a parameter in place, so there is no id to react to.
   */
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly detail = signal<UserDetail | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isBusy = signal(false);

  protected readonly isPasswordOpen = signal(false);
  protected readonly isDeleteOpen = signal(false);

  /**
   * Whether this is the signed-in administrator's own account.
   *
   * The backend refuses a self role-change, self-suspension and self-deletion, so
   * offering the buttons would be offering an action that always fails. Better to
   * say why up front.
   */
  protected readonly isSelf = computed(
    () => this.detail()?.account.username === this.auth.getUsername()
  );

  protected readonly roleForm = this.fb.nonNullable.group({
    role: ['MEMBER' as Role, [Validators.required]],
  });

  protected readonly passwordForm = this.fb.nonNullable.group({
    newPassword: ['', [Validators.required, Validators.minLength(8)]],
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.isLoading.set(true);
    this.admin.getUser(this.id).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.roleForm.setValue({ role: detail.account.role });
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.notifications.showError(describeError(err, 'Could not load that account.'));
        this.router.navigate(['/admin/users']);
      },
    });
  }

  // ------------------------------------------------------------------ actions

  protected saveRole(): void {
    const role = this.roleForm.getRawValue().role;
    if (role === this.detail()?.account.role) {
      return;
    }
    this.run(this.admin.updateUser(this.id, { role }), `Role changed to ${ROLE_LABELS[role]}.`);
  }

  protected setActive(active: boolean): void {
    this.run(
      this.admin.updateUser(this.id, { active }),
      active ? 'Account reinstated.' : 'Account suspended and signed out.'
    );
  }

  protected unlock(): void {
    this.run(this.admin.unlock(this.id), 'Lock cleared.');
  }

  protected revokeSessions(): void {
    this.run(this.admin.revokeSessions(this.id), 'Signed out everywhere.');
  }

  protected setPassword(): void {
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      return;
    }

    const newPassword = this.passwordForm.getRawValue().newPassword;
    this.isBusy.set(true);

    this.admin.setPassword(this.id, newPassword).subscribe({
      next: () => {
        this.isBusy.set(false);
        this.isPasswordOpen.set(false);
        this.passwordForm.reset({ newPassword: '' });
        this.notifications.showSuccess('Password set. Every session for it has ended.');
        this.load();
      },
      error: (err) => {
        this.isBusy.set(false);
        this.notifications.showError(describeError(err, 'Could not set that password.'));
      },
    });
  }

  protected confirmDelete(): void {
    const username = this.detail()?.account.username ?? 'That account';
    this.isBusy.set(true);

    this.admin.deleteUser(this.id).subscribe({
      next: () => {
        this.isBusy.set(false);
        this.isDeleteOpen.set(false);
        this.notifications.showSuccess(`${username} and everything it held has been deleted.`);
        this.router.navigate(['/admin/users']);
      },
      error: (err) => {
        this.isBusy.set(false);
        this.isDeleteOpen.set(false);
        this.notifications.showError(describeError(err, 'Could not delete that account.'));
      },
    });
  }

  /**
   * Runs an action, then reloads.
   *
   * The reload is not laziness: several of these change more than the field they
   * name — suspending also ends sessions, unlocking also clears the failure
   * counter — and re-reading is how the screen stays truthful about all of it.
   */
  private run(action: Observable<unknown>, success: string): void {
    this.isBusy.set(true);
    action.subscribe({
      next: () => {
        this.isBusy.set(false);
        this.notifications.showSuccess(success);
        this.load();
      },
      error: (err) => {
        this.isBusy.set(false);
        this.notifications.showError(describeError(err, 'That did not work.'));
        // Re-read so a refused change does not leave the form showing it.
        this.load();
      },
    });
  }

  // --------------------------------------------------------------- formatting

  /**
   * The fallback is a parameter because null means different things in different
   * places: an account that has never signed in, versus one created before the
   * application recorded a join date.
   */
  protected exact(iso: string | null, fallback = 'Never'): string {
    return iso
      ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
      : fallback;
  }
}
