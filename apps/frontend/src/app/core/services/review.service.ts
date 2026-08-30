import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import {
  MerchantAssignment,
  ReviewAction,
  ReviewQueue,
} from '../models/review.models';

@Injectable({ providedIn: 'root' })
export class ReviewService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private readonly count = signal(0);

  /**
   * How many rows are waiting, shared across the application.
   *
   * Held here rather than in the review screen because the navigation badge and
   * the import result both need it, and three copies of one number is three
   * chances for the badge to be wrong after an action somewhere else.
   */
  readonly waiting = this.count.asReadonly();

  private get endpoint(): string {
    return `${this.baseUrl}/api/review`;
  }

  /** The queue itself, largest group first. */
  merchants(): Observable<ReviewQueue> {
    return this.http
      .get<ReviewQueue>(`${this.endpoint}/merchants`)
      .pipe(tap((queue) => this.count.set(queue.transactions)));
  }

  /** Counts only — what the badge needs, without listing anything. */
  refreshCount(): Observable<ReviewQueue> {
    return this.http
      .get<ReviewQueue>(`${this.endpoint}/summary`)
      .pipe(tap((queue) => this.count.set(queue.transactions)));
  }

  /** Accepts the suggested category for one merchant, unchanged. */
  approve(merchantHash: string): Observable<ReviewAction> {
    return this.http.post<ReviewAction>(
      `${this.endpoint}/merchants/${merchantHash}/approve`,
      {}
    );
  }

  /** Refiles one merchant, and by default remembers the decision as a rule. */
  assign(merchantHash: string, payload: MerchantAssignment): Observable<ReviewAction> {
    return this.http.post<ReviewAction>(
      `${this.endpoint}/merchants/${merchantHash}/assign`,
      payload
    );
  }

  approveAll(): Observable<ReviewAction> {
    return this.http.post<ReviewAction>(`${this.endpoint}/approve-all`, {});
  }
}
