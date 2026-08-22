import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import {
  Transaction,
  CreateTransactionRequest,
  UpdateTransactionRequest,
} from '../models/transaction.models';

@Injectable({ providedIn: 'root' })
export class TransactionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  
  private readonly transactionsSubject = new BehaviorSubject<Transaction[]>([]);
  public transactions$ = this.transactionsSubject.asObservable();

  getTransactions(): Observable<Transaction[]> {
    return this.http.get<Transaction[]>(`${this.baseUrl}/api/transactions`).pipe(
      tap((transactions) => this.transactionsSubject.next(transactions))
    );
  }

  getTransactionById(id: string): Observable<Transaction> {
    return this.http.get<Transaction>(`${this.baseUrl}/api/transactions/${id}`);
  }

  createTransaction(payload: CreateTransactionRequest): Observable<Transaction> {
    return this.http.post<Transaction>(`${this.baseUrl}/api/transactions`, payload).pipe(
      tap((transaction) => {
        const currentTransactions = this.transactionsSubject.value;
        this.transactionsSubject.next([...currentTransactions, transaction]);
      })
    );
  }

  updateTransaction(id: string, payload: UpdateTransactionRequest): Observable<Transaction> {
    return this.http.put<Transaction>(`${this.baseUrl}/api/transactions/${id}`, payload).pipe(
      tap((transaction) => {
        const currentTransactions = this.transactionsSubject.value;
        const index = currentTransactions.findIndex((t) => t.id === id);
        if (index !== -1) {
          currentTransactions[index] = transaction;
          this.transactionsSubject.next([...currentTransactions]);
        }
      })
    );
  }

  deleteTransaction(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/api/transactions/${id}`).pipe(
      tap(() => {
        const currentTransactions = this.transactionsSubject.value.filter((t) => t.id !== id);
        this.transactionsSubject.next(currentTransactions);
      })
    );
  }
}
