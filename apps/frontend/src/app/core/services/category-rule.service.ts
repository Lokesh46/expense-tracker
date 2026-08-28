import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import {
  CategoryRule,
  CreateCategoryRuleRequest,
  MatchTypeOption,
  UpdateCategoryRuleRequest,
} from '../models/category-rule.models';

@Injectable({ providedIn: 'root' })
export class CategoryRuleService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get endpoint(): string {
    return `${this.baseUrl}/api/category-rules`;
  }

  /**
   * Not cached, unlike categories. Only one screen reads rules, and the order
   * is editable there — a cache would just be a second copy to keep in step.
   */
  load(): Observable<CategoryRule[]> {
    return this.http.get<CategoryRule[]>(this.endpoint);
  }

  matchTypes(): Observable<MatchTypeOption[]> {
    return this.http.get<MatchTypeOption[]>(`${this.endpoint}/match-types`);
  }

  create(payload: CreateCategoryRuleRequest): Observable<CategoryRule> {
    return this.http.post<CategoryRule>(this.endpoint, payload);
  }

  update(id: number, payload: UpdateCategoryRuleRequest): Observable<CategoryRule> {
    return this.http.put<CategoryRule>(`${this.endpoint}/${id}`, payload);
  }

  /**
   * Moving one rule renumbers the rest, so the server answers with the whole
   * list rather than the single rule that moved.
   */
  moveUp(id: number): Observable<CategoryRule[]> {
    return this.http.put<CategoryRule[]>(`${this.endpoint}/${id}/move-up`, {});
  }

  moveDown(id: number): Observable<CategoryRule[]> {
    return this.http.put<CategoryRule[]>(`${this.endpoint}/${id}/move-down`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.endpoint}/${id}`);
  }
}
