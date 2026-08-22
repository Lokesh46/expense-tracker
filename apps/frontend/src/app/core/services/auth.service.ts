import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, Observable, tap } from 'rxjs';

import { API_BASE_URL } from '../tokens/api-base-url.token';
import { AuthRequest, AuthResponse, RegisterRequest } from '../models/auth.models';

const TOKEN_KEY = 'auth_token';

interface JwtClaims {
  sub?: string;
  exp?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private readonly authState = new BehaviorSubject<boolean>(this.hasValidToken());
  readonly isAuthenticated$ = this.authState.asObservable();

  private readonly expiryCheckInterval = 30_000;

  constructor() {
    // An expired token is worse than no token: the UI would render as signed in
    // and then fail every request with a 401.
    if (this.hasToken() && !this.hasValidToken()) {
      this.clearToken();
    }
    this.startExpiryWatcher();
  }

  authenticate(payload: AuthRequest, options?: { rememberMe?: boolean }): Observable<AuthResponse> {
    const rememberMe = options?.rememberMe ?? true;
    return this.http
      .post<AuthResponse>(`${this.baseUrl}/authenticate`, payload)
      .pipe(tap((response) => this.persistToken(response.token, rememberMe)));
  }

  register(payload: RegisterRequest): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.baseUrl}/register`, payload);
  }

  logout(): void {
    this.clearToken();
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
  }

  /** The signed-in username, read from the token's subject claim. */
  getUsername(): string | null {
    return this.claims()?.sub ?? null;
  }

  /** When the session expires, or null if there is no usable token. */
  getExpiry(): Date | null {
    const exp = this.claims()?.exp;
    return exp ? new Date(exp * 1000) : null;
  }

  private claims(): JwtClaims | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }
    const parts = token.split('.');
    if (parts.length < 2) {
      return null;
    }
    try {
      // Base64url differs from base64 in two characters, and atob rejects it.
      const json = atob(parts[1].replaceAll('-', '+').replaceAll('_', '/'));
      return JSON.parse(json) as JwtClaims;
    } catch {
      return null;
    }
  }

  private hasToken(): boolean {
    return this.getToken() !== null;
  }

  private hasValidToken(): boolean {
    const exp = this.claims()?.exp;
    if (!exp) {
      return false;
    }
    return exp * 1000 > Date.now();
  }

  private startExpiryWatcher(): void {
    setInterval(() => {
      if (this.hasToken() && !this.hasValidToken()) {
        this.clearToken();
      }
    }, this.expiryCheckInterval);
  }

  private persistToken(token: string, rememberMe: boolean): void {
    if (!token) {
      return;
    }
    const storage = rememberMe ? localStorage : sessionStorage;
    const other = rememberMe ? sessionStorage : localStorage;

    storage.setItem(TOKEN_KEY, token);
    other.removeItem(TOKEN_KEY);
    this.authState.next(true);
  }

  private clearToken(): void {
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    this.authState.next(false);
  }
}
