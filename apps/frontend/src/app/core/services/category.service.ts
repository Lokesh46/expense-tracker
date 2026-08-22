import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, BehaviorSubject, of, throwError } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import { Category, CreateCategoryRequest, UpdateCategoryRequest } from '../models/category.models';

@Injectable({ providedIn: 'root' })
export class CategoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  
  private readonly categoriesSubject = new BehaviorSubject<Category[]>([]);
  public categories$ = this.categoriesSubject.asObservable();

  getCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${this.baseUrl}/api/categories`).pipe(
      tap((categories) => this.categoriesSubject.next(categories)),
      catchError((err) => {
        // If the backend does not expose a GET for categories yet, return current cached categories
        return of(this.categoriesSubject.value);
      })
    );
  }

  getCategoryById(id: string): Observable<Category> {
    return this.http.get<Category>(`${this.baseUrl}/api/categories/${id}`);
  }

  createCategory(payload: CreateCategoryRequest): Observable<Category> {
    return this.http.post<Category>(`${this.baseUrl}/api/categories`, payload).pipe(
      tap((category) => {
        const currentCategories = this.categoriesSubject.value;
        this.categoriesSubject.next([...currentCategories, category]);
      }),
      catchError((err) => {
        // propagate the error so caller can show notification
        return throwError(() => err);
      })
    );
  }

  updateCategory(id: string, payload: UpdateCategoryRequest): Observable<Category> {
    return this.http.put<Category>(`${this.baseUrl}/api/categories/${id}`, payload).pipe(
      tap((category) => {
        const currentCategories = this.categoriesSubject.value;
        const index = currentCategories.findIndex((c) => c.id === id);
        if (index !== -1) {
          currentCategories[index] = category;
          this.categoriesSubject.next([...currentCategories]);
        }
      }),
      catchError((err) => {
        return throwError(() => err);
      })
    );
  }

  deleteCategory(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/api/categories/${id}`).pipe(
      tap(() => {
        const currentCategories = this.categoriesSubject.value.filter((c) => c.id !== id);
        this.categoriesSubject.next(currentCategories);
      }),
      catchError((err) => {
        return throwError(() => err);
      })
    );
  }
}
