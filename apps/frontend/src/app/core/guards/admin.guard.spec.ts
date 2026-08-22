import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { adminGuard } from './admin.guard';
import { AccountService } from '../services/account.service';
import { AuthService } from '../services/auth.service';
import { Account, Role } from '../models/user.models';

class FakeAuthService {
  token: string | null = 'a-token';
  role: Role | null = null;

  getToken(): string | null {
    return this.token;
  }
  getRole(): Role | null {
    return this.role;
  }
}

class FakeAccountService {
  loads = 0;
  account: Account | null = null;
  fails = false;

  load(): Observable<Account> {
    this.loads += 1;
    if (this.fails) {
      return throwError(() => new Error('offline'));
    }
    return of(this.account as Account);
  }
}

const account = (role: Role): Account => ({
  id: 1,
  username: 'mia',
  email: null,
  role,
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: null,
  lastLoginIp: null,
  loginCount: 0,
});

describe('adminGuard', () => {
  let auth: FakeAuthService;
  let accounts: FakeAccountService;
  let router: Router;

  beforeEach(() => {
    auth = new FakeAuthService();
    accounts = new FakeAccountService();

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: AccountService, useValue: accounts },
      ],
    });

    router = TestBed.inject(Router);
  });

  function run(): boolean | UrlTree | Observable<boolean | UrlTree> {
    return TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)
    ) as boolean | UrlTree | Observable<boolean | UrlTree>;
  }

  function resolve(result: ReturnType<typeof run>): boolean | UrlTree {
    if (result instanceof Observable) {
      let value: boolean | UrlTree = false;
      result.subscribe((v) => (value = v));
      return value;
    }
    return result;
  }

  it('sends a signed-out visitor to sign in', () => {
    auth.token = null;
    const result = resolve(run());

    expect(result instanceof UrlTree).toBe(true);
    expect(router.serializeUrl(result as UrlTree)).toContain('/login');
  });

  /** The common case, and it must not cost a request. */
  it('lets an administrator through on the token alone', () => {
    auth.role = 'ADMIN';

    expect(resolve(run())).toBe(true);
    expect(accounts.loads).toBe(0);
  });

  it('redirects a member to the dashboard', () => {
    auth.role = 'MEMBER';
    const result = resolve(run());

    expect(router.serializeUrl(result as UrlTree)).toContain('/dashboard');
    expect(accounts.loads).toBe(0);
  });

  /**
   * A token from an older build has no role claim. Assuming the worse of the two
   * answers would lock a genuine administrator out of their own screens, so the
   * profile is asked instead.
   */
  it('falls back to the profile when the token names no role', () => {
    auth.role = null;
    accounts.account = account('ADMIN');

    expect(resolve(run())).toBe(true);
    expect(accounts.loads).toBe(1);
  });

  it('redirects when the profile says member', () => {
    auth.role = null;
    accounts.account = account('MEMBER');

    const result = resolve(run());
    expect(router.serializeUrl(result as UrlTree)).toContain('/dashboard');
  });

  /** Failing to reach the API is not grounds for letting anyone in. */
  it('redirects when the profile cannot be loaded', () => {
    auth.role = null;
    accounts.fails = true;

    const result = resolve(run());
    expect(router.serializeUrl(result as UrlTree)).toContain('/dashboard');
  });
});
