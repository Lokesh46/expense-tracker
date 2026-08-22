import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { PageResponse } from '../models/transaction.models';
import {
  ActivityEntry,
  ActivityQuery,
  AdminStats,
  CreateUserRequest,
  UpdateUserRequest,
  UserDetail,
  UserQuery,
  UserSummary,
} from '../models/user.models';
import { API_BASE_URL } from '../tokens/api-base-url.token';

/**
 * The admin API.
 *
 * There is deliberately no client-side cache. Every list is filtered, sorted and
 * paged in the database, so a cached page would be a different result set from
 * the one being asked for — and on this screen in particular, a stale row is a
 * suspension that looks like it did not take.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get users(): string {
    return `${this.baseUrl}/api/admin/users`;
  }

  private get activity(): string {
    return `${this.baseUrl}/api/admin/activity`;
  }

  // ------------------------------------------------------------------- users

  searchUsers(query: UserQuery): Observable<PageResponse<UserSummary>> {
    return this.http.get<PageResponse<UserSummary>>(this.users, {
      params: this.userParams(query),
    });
  }

  getUser(id: number): Observable<UserDetail> {
    return this.http.get<UserDetail>(`${this.users}/${id}`);
  }

  getStats(): Observable<AdminStats> {
    return this.http.get<AdminStats>(`${this.users}/stats`);
  }

  createUser(payload: CreateUserRequest): Observable<UserSummary> {
    return this.http.post<UserSummary>(this.users, payload);
  }

  /** PATCH, so an omitted field is left alone rather than cleared. */
  updateUser(id: number, payload: UpdateUserRequest): Observable<UserSummary> {
    return this.http.patch<UserSummary>(`${this.users}/${id}`, payload);
  }

  setPassword(id: number, newPassword: string): Observable<void> {
    return this.http.post<void>(`${this.users}/${id}/password`, { newPassword });
  }

  unlock(id: number): Observable<UserSummary> {
    return this.http.post<UserSummary>(`${this.users}/${id}/unlock`, {});
  }

  /** Ends every session for the account, leaving its password alone. */
  revokeSessions(id: number): Observable<void> {
    return this.http.post<void>(`${this.users}/${id}/revoke-sessions`, {});
  }

  deleteUser(id: number): Observable<void> {
    return this.http.delete<void>(`${this.users}/${id}`);
  }

  // ---------------------------------------------------------------- activity

  searchActivity(query: ActivityQuery): Observable<PageResponse<ActivityEntry>> {
    return this.http.get<PageResponse<ActivityEntry>>(this.activity, {
      params: this.activityParams(query),
    });
  }

  exportActivityCsv(query: ActivityQuery): Observable<Blob> {
    return this.http.get(`${this.activity}/export`, {
      // Paging is meaningless for an export: it is the whole filtered set, up to
      // the cap the backend applies.
      params: this.activityParams({ ...query, page: undefined, size: undefined }),
      responseType: 'blob',
    });
  }

  // ----------------------------------------------------------------- params

  private userParams(query: UserQuery): HttpParams {
    let params = this.paging(query.page, query.size);

    params = append(params, 'search', query.search);
    params = append(params, 'role', query.role);
    params = append(params, 'status', query.status);

    if (query.sortBy) {
      params = params.set('sort', `${query.sortBy},${query.sortDir ?? 'desc'}`);
    }
    return params;
  }

  private activityParams(query: ActivityQuery): HttpParams {
    let params = this.paging(query.page, query.size);

    params = append(params, 'username', query.username);
    params = append(params, 'action', query.action);
    params = append(params, 'from', query.from);
    params = append(params, 'to', query.to);
    // Only sent when true: `adverseOnly=false` would be read as a filter rather
    // than as its absence.
    if (query.adverseOnly) {
      params = params.set('adverseOnly', 'true');
    }
    return params;
  }

  private paging(page?: number, size?: number): HttpParams {
    let params = new HttpParams();
    if (page !== undefined) {
      params = params.set('page', String(page));
    }
    if (size !== undefined) {
      params = params.set('size', String(size));
    }
    return params;
  }
}

/**
 * Adds a parameter only when it carries a value.
 *
 * An empty `search=` or `role=` is a filter matching nothing, not the absence of
 * one, so blanks have to be dropped rather than sent.
 */
function append(params: HttpParams, key: string, value: unknown): HttpParams {
  if (value === null || value === undefined || value === '') {
    return params;
  }
  return params.set(key, String(value));
}
