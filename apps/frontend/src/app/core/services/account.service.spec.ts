import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';

import { AccountService } from './account.service';
import { AuthService } from './auth.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { Account } from '../models/user.models';

const mia: Account = {
  id: 7,
  username: 'mia',
  email: 'mia@example.com',
  role: 'MEMBER',
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: '2026-08-22T09:14:00Z',
  lastLoginIp: '203.0.113.4',
  loginCount: 38,
};

describe('AccountService', () => {
  let service: AccountService;
  let http: HttpTestingController;
  let authState: BehaviorSubject<boolean>;

  beforeEach(() => {
    authState = new BehaviorSubject<boolean>(true);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
        { provide: AuthService, useValue: { isAuthenticated$: authState.asObservable() } },
      ],
    });

    service = TestBed.inject(AccountService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function load(account: Account = mia): void {
    service.load().subscribe();
    http.expectOne('/api/account/me').flush(account);
  }

  it('starts with no profile and fills from the server', () => {
    expect(service.profile()).toBeNull();
    load();
    expect(service.profile()).toEqual(mia);
  });

  it('keeps the profile up to date after an email change', () => {
    load();

    service.updateEmail('new@example.com').subscribe();
    const request = http.expectOne({ url: '/api/account/email', method: 'PUT' });
    expect(request.request.body).toEqual({ email: 'new@example.com' });
    request.flush({ ...mia, email: 'new@example.com' });

    expect(service.profile()?.email).toBe('new@example.com');
  });

  /**
   * The sidebar decides whether to offer the admin screens from this profile. A
   * stale one would show the previous account's navigation to whoever signs in
   * next on the same browser.
   */
  it('drops the profile when the session ends', () => {
    load({ ...mia, role: 'ADMIN' });
    expect(service.profile()?.role).toBe('ADMIN');

    authState.next(false);

    expect(service.profile()).toBeNull();
  });

  it('sends the current password along with the new one', () => {
    service.changePassword('old-one', 'BrandNewPass1!').subscribe();

    const request = http.expectOne({ url: '/api/account/password', method: 'POST' });
    // Required even though the request is authenticated: a token left behind on a
    // shared machine should not be enough to take the account over for good.
    expect(request.request.body).toEqual({
      currentPassword: 'old-one',
      newPassword: 'BrandNewPass1!',
    });

    request.flush(null);
  });

  it('pages its own activity', () => {
    service.myActivity(2, 10).subscribe();

    const request = http.expectOne((req) => req.url === '/api/account/activity');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('10');

    request.flush({
      content: [],
      page: 2,
      size: 10,
      totalElements: 0,
      totalPages: 0,
      first: false,
      last: true,
    });
  });
});
