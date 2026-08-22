import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import { Budget, SaveBudgetRequest } from '../models/budget.models';

@Injectable({ providedIn: 'root' })
export class BudgetService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get endpoint(): string {
    return `${this.baseUrl}/api/budgets`;
  }

  /**
   * @param month yyyy-MM. Spend is calculated against this month, so passing a
   *              past month reports what was actually spent then.
   */
  load(month?: string): Observable<Budget[]> {
    const params = month ? new HttpParams().set('month', month) : undefined;
    return this.http.get<Budget[]>(this.endpoint, { params });
  }

  create(payload: SaveBudgetRequest): Observable<Budget> {
    return this.http.post<Budget>(this.endpoint, payload);
  }

  update(id: number, payload: SaveBudgetRequest): Observable<Budget> {
    return this.http.put<Budget>(`${this.endpoint}/${id}`, payload);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.endpoint}/${id}`);
  }
}
