import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import {
  Category,
  CreateCategoryRequest,
  UpdateCategoryRequest,
} from '../models/category.models';

@Injectable({ providedIn: 'root' })
export class CategoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /**
   * Categories are small, rarely change, and are needed by almost every screen
   * (as a filter, a picker, a colour lookup), so they are cached here and every
   * mutation refreshes the cache.
   */
  private readonly cache = signal<Category[]>([]);

  readonly categories = this.cache.asReadonly();

  /** Colour lookup by id, used by the dashboard charts and transaction rows. */
  readonly colorById = computed(() => {
    const map = new Map<number, string>();
    for (const category of this.cache()) {
      map.set(category.id, category.color);
    }
    return map;
  });

  private get endpoint(): string {
    return `${this.baseUrl}/api/categories`;
  }

  load(): Observable<Category[]> {
    return this.http
      .get<Category[]>(this.endpoint)
      .pipe(tap((categories) => this.cache.set(categories)));
  }

  create(payload: CreateCategoryRequest): Observable<Category> {
    return this.http
      .post<Category>(this.endpoint, payload)
      .pipe(tap((created) => this.cache.update((all) => [...all, created].sort(byName))));
  }

  update(id: number, payload: UpdateCategoryRequest): Observable<Category> {
    return this.http.put<Category>(`${this.endpoint}/${id}`, payload).pipe(
      tap((updated) =>
        this.cache.update((all) => all.map((c) => (c.id === id ? updated : c)).sort(byName))
      )
    );
  }

  delete(id: number): Observable<void> {
    return this.http
      .delete<void>(`${this.endpoint}/${id}`)
      .pipe(tap(() => this.cache.update((all) => all.filter((c) => c.id !== id))));
  }
}

function byName(a: Category, b: Category): number {
  return a.name.localeCompare(b.name);
}
