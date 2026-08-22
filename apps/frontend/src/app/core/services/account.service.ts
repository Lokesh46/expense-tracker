import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { PageResponse } from '../models/transaction.models';
import { Account, ActivityEntry } from '../models/user.models';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { AuthService } from './auth.service';

/**
 * Your own account.
 *
 * The loaded profile is held in a signal because several places need it at once
 * — the sidebar decides whether to offer the admin screens, the account page
 * renders it — and it is the authoritative answer to "what am I allowed to do".
 * The token carries a role too, and the UI uses it to avoid a blank frame on
 * first paint, but a token is a claim about the past: an administrator may have
 * changed something since it was issued.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get endpoint(): string {
    return `${this.baseUrl}/api/account`;
  }

  private readonly account = signal<Account | null>(null);

  /** The loaded profile, or null before the first load. */
  readonly profile = this.account.asReadonly();

  constructor() {
    // Dropped the moment the session ends, so the next person to sign in on this
    // browser cannot be shown the previous one's account — or, worse, the
    // previous one's admin navigation.
    inject(AuthService).isAuthenticated$.subscribe((authenticated) => {
      if (!authenticated) {
        this.account.set(null);
      }
    });
  }

  load(): Observable<Account> {
    return this.http
      .get<Account>(`${this.endpoint}/me`)
      .pipe(tap((account) => this.account.set(account)));
  }

  updateEmail(email: string): Observable<Account> {
    return this.http
      .put<Account>(`${this.endpoint}/email`, { email })
      .pipe(tap((account) => this.account.set(account)));
  }

  /**
   * Changes your password. Every session ends, this one included, so the caller
   * should expect to sign in again rather than treat the next 401 as a fault.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${this.endpoint}/password`, { currentPassword, newPassword });
  }

  myActivity(page = 0, size = 20): Observable<PageResponse<ActivityEntry>> {
    return this.http.get<PageResponse<ActivityEntry>>(`${this.endpoint}/activity`, {
      params: new HttpParams().set('page', String(page)).set('size', String(size)),
    });
  }

  /** Called on sign-out, so the next account does not inherit this one's profile. */
  clear(): void {
    this.account.set(null);
  }
}
