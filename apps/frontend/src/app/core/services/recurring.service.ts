import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import { RecurringTransaction, SaveRecurringRequest } from '../models/recurring.models';

@Injectable({ providedIn: 'root' })
export class RecurringService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get endpoint(): string {
    return `${this.baseUrl}/api/recurring`;
  }

  load(): Observable<RecurringTransaction[]> {
    return this.http.get<RecurringTransaction[]>(this.endpoint);
  }

  create(payload: SaveRecurringRequest): Observable<RecurringTransaction> {
    return this.http.post<RecurringTransaction>(this.endpoint, payload);
  }

  update(id: number, payload: SaveRecurringRequest): Observable<RecurringTransaction> {
    return this.http.put<RecurringTransaction>(`${this.endpoint}/${id}`, payload);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.endpoint}/${id}`);
  }

  /** Generates anything already due instead of waiting for the nightly sweep. */
  runNow(): Observable<{ created: number }> {
    return this.http.post<{ created: number }>(`${this.endpoint}/run`, {});
  }
}
