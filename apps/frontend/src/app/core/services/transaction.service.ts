import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import {
  CreateTransactionRequest,
  DateOrder,
  ImportResult,
  PageResponse,
  Transaction,
  TransactionQuery,
  UpdateTransactionRequest,
} from '../models/transaction.models';

@Injectable({ providedIn: 'root' })
export class TransactionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private get endpoint(): string {
    return `${this.baseUrl}/api/transactions`;
  }

  /**
   * Filtered, sorted and paged search.
   *
   * There is deliberately no client-side cache: filtering happens in the
   * database, so a cached list would be a different result set from the one
   * being asked for.
   */
  search(query: TransactionQuery): Observable<PageResponse<Transaction>> {
    return this.http.get<PageResponse<Transaction>>(this.endpoint, {
      params: this.toParams(query),
    });
  }

  getById(id: number): Observable<Transaction> {
    return this.http.get<Transaction>(`${this.endpoint}/${id}`);
  }

  create(payload: CreateTransactionRequest): Observable<Transaction> {
    return this.http.post<Transaction>(this.endpoint, payload);
  }

  update(id: number, payload: UpdateTransactionRequest): Observable<Transaction> {
    return this.http.put<Transaction>(`${this.endpoint}/${id}`, payload);
  }

  /**
   * Records that a flagged row is genuine after all, clearing the badge.
   *
   * Two identical payments on one day are ordinary, and no rule can tell that
   * apart from a statement imported twice — only the account holder can.
   */
  markNotDuplicate(id: number): Observable<Transaction> {
    return this.http.put<Transaction>(`${this.endpoint}/${id}/not-duplicate`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.endpoint}/${id}`);
  }

  /** Downloads the transactions matching the current filters as CSV. */
  exportCsv(query: TransactionQuery): Observable<Blob> {
    return this.http.get(`${this.endpoint}/export`, {
      params: this.toParams({ ...query, page: undefined, size: undefined }),
      responseType: 'blob',
    });
  }

  /**
   * Uploads a statement.
   *
   * Both options describe the file rather than the account, so they travel with
   * it: the next statement may well come from a different bank.
   */
  importCsv(file: File, dateOrder: DateOrder, defaultCurrency: string): Observable<ImportResult> {
    const form = new FormData();
    form.append('file', file);
    form.append('dateOrder', dateOrder);
    form.append('defaultCurrency', defaultCurrency);
    return this.http.post<ImportResult>(`${this.endpoint}/import`, form);
  }

  /**
   * Builds the query string, omitting anything unset.
   *
   * Sending `categoryId=` or `search=` as empty values would be treated as a
   * filter by the backend rather than as "no filter".
   */
  private toParams(query: TransactionQuery): HttpParams {
    let params = new HttpParams();

    const append = (key: string, value: unknown) => {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    };

    append('page', query.page);
    append('size', query.size);
    append('categoryId', query.categoryId);
    append('from', query.from);
    append('to', query.to);
    append('minAmount', query.minAmount);
    append('maxAmount', query.maxAmount);
    append('paymentMethod', query.paymentMethod);
    append('search', query.search?.trim());
    append('possibleDuplicate', query.possibleDuplicate);

    if (query.sortBy) {
      params = params.set('sort', `${query.sortBy},${query.sortDir ?? 'desc'}`);
    }

    return params;
  }
}
