import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

class FakeAuthService {
  token: string | null = 'a-token';
  logouts = 0;

  getToken(): string | null {
    return this.token;
  }
  logout(): void {
    this.logouts += 1;
    this.token = null;
  }
}

class FakeRouter {
  navigations: unknown[][] = [];
  navigate(commands: unknown[]): Promise<boolean> {
    this.navigations.push(commands);
    return Promise.resolve(true);
  }
}

class FakeNotificationService {
  warnings: string[] = [];
  showWarning(message: string): void {
    this.warnings.push(message);
  }
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: FakeAuthService;
  let router: FakeRouter;
  let notifications: FakeNotificationService;

  beforeEach(() => {
    auth = new FakeAuthService();
    router = new FakeRouter();
    notifications = new FakeNotificationService();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  function request(url: string, status: number, body: object = {}): void {
    http.get(url).subscribe({ next: () => undefined, error: () => undefined });
    backend.expectOne(url).flush(body, { status, statusText: 'x' });
  }

  it('attaches the bearer token', () => {
    http.get('/api/categories').subscribe();

    const sent = backend.expectOne('/api/categories');
    expect(sent.request.headers.get('Authorization')).toBe('Bearer a-token');

    sent.flush([]);
  });

  it('leaves an Authorization header already set alone', () => {
    http.get('/api/categories', { headers: { Authorization: 'Bearer other' } }).subscribe();

    const sent = backend.expectOne('/api/categories');
    expect(sent.request.headers.get('Authorization')).toBe('Bearer other');

    sent.flush([]);
  });

  it('ends the session on a 401 and says why', () => {
    request('/api/categories', 401, { message: 'This account has been suspended.' });

    expect(auth.logouts).toBe(1);
    expect(router.navigations).toEqual([['/login']]);
    // The backend's explanation is the useful part. Redirecting silently is how a
    // suspended user concludes the application is broken.
    expect(notifications.warnings).toEqual(['This account has been suspended.']);
  });

  /**
   * The bug this guards against: a 403 used to end the session too. Once there
   * were endpoints a signed-in member is legitimately refused, opening an admin
   * URL as a member would have signed them out of the whole application.
   */
  it('does not end the session on a 403', () => {
    request('/api/admin/users', 403, { message: 'You do not have permission to do that.' });

    expect(auth.logouts).toBe(0);
    expect(router.navigations).toEqual([]);
    expect(notifications.warnings).toEqual([]);
  });

  /** A 401 here is a wrong password, not a dead session. */
  it('does not end the session on a failed sign-in', () => {
    request('/authenticate', 401, { message: 'Invalid username or password' });

    expect(auth.logouts).toBe(0);
    expect(router.navigations).toEqual([]);
  });

  it('leaves other failures to the caller', () => {
    request('/api/admin/users/7', 409, { message: 'This is the only administrator.' });

    expect(auth.logouts).toBe(0);
    expect(router.navigations).toEqual([]);
  });

  it('passes the error on so the caller can still report it', () => {
    let seen: unknown = null;
    http.get('/api/categories').subscribe({ error: (err) => (seen = err) });
    backend.expectOne('/api/categories').flush({ message: 'no' }, { status: 401, statusText: 'x' });

    expect(seen).not.toBeNull();
  });
});
