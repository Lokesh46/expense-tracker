import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, Observable, tap } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import { AuthRequest, AuthResponse, RegisterRequest } from '../models/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private readonly authState = new BehaviorSubject<boolean>(this.hasToken());
  public readonly isAuthenticated$ = this.authState.asObservable();

  private readonly expiryCheckInterval = 60 * 1000; // 1 minute

  constructor() {
    this.startExpiryWatcher();
  }

  authenticate(payload: AuthRequest, options?: { rememberMe?: boolean }): Observable<AuthResponse> {
    const rememberMe = options?.rememberMe ?? true;
    return this.http
      .post<AuthResponse>(`${this.baseUrl}/authenticate`, payload)
      .pipe(tap((response) => this.persistToken(response.token, rememberMe)));
  }

  register(payload: RegisterRequest): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/register`, payload);
  }

  logout(): void {
    localStorage.removeItem('auth_token');
    sessionStorage.removeItem('auth_token');
    this.authState.next(false);
  }

  getToken(): string | null {
    return localStorage.getItem('auth_token') ?? sessionStorage.getItem('auth_token');
  }

  private startExpiryWatcher(): void {
    setInterval(() => {
      const token = this.getToken();
      if (token && this.isTokenExpired(token)) {
        this.logout();
      }
    }, this.expiryCheckInterval);
  }

  private isTokenExpired(token: string): boolean {
    try {
      const parts = token.split('.');
      if (parts.length < 2) return false;
      const payload = JSON.parse(
        atob(parts[1].replaceAll('-', '+').replaceAll('_', '/'))
      );
      if (!payload.exp) return false;
      const now = Math.floor(Date.now() / 1000);
      return payload.exp <= now;
    } catch {
      return false;
    }
  }

  private persistToken(token: string, rememberMe: boolean): void {
    if (!token) {
      return;
    }

    const storage = rememberMe ? localStorage : sessionStorage;
    const otherStorage = rememberMe ? sessionStorage : localStorage;

    storage.setItem('auth_token', token);
    otherStorage.removeItem('auth_token');
    this.authState.next(true);
  }

  private hasToken(): boolean {
    return !!(localStorage.getItem('auth_token') || sessionStorage.getItem('auth_token'));
  }
}
