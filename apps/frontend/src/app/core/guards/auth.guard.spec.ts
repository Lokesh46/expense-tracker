import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { provideRouter } from '@angular/router';

import { authGuard } from './auth.guard';
import { guestGuard } from './guest.guard';
import { AuthService } from '../services/auth.service';

class FakeAuthService {
  token: string | null = null;
  getToken(): string | null {
    return this.token;
  }
}

describe('route guards', () => {
  let auth: FakeAuthService;
  let router: Router;

  beforeEach(() => {
    auth = new FakeAuthService();

    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });

    router = TestBed.inject(Router);
  });

  function runAuthGuard(url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)
    ) as boolean | UrlTree;
  }

  function runGuestGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      guestGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)
    ) as boolean | UrlTree;
  }

  describe('authGuard', () => {
    it('allows a signed-in user through', () => {
      auth.token = 'a-token';
      expect(runAuthGuard('/transactions')).toBe(true);
    });

    it('sends a signed-out user to sign in', () => {
      const result = runAuthGuard('/transactions');

      expect(result instanceof UrlTree).toBe(true);
      expect(router.serializeUrl(result as UrlTree)).toContain('/login');
    });

    it('remembers where the user was heading', () => {
      const result = runAuthGuard('/budgets');

      // Without this a deep link is lost and the user lands on the dashboard
      // after signing in, having to navigate again.
      expect(router.serializeUrl(result as UrlTree)).toContain('returnUrl=%2Fbudgets');
    });

    it('does not bother recording the dashboard as a return target', () => {
      const result = runAuthGuard('/dashboard');
      expect(router.serializeUrl(result as UrlTree)).not.toContain('returnUrl');
    });
  });

  describe('guestGuard', () => {
    it('lets a signed-out visitor reach the sign-in screen', () => {
      expect(runGuestGuard()).toBe(true);
    });

    it('redirects a signed-in user away from the sign-in screen', () => {
      auth.token = 'a-token';
      const result = runGuestGuard();

      expect(result instanceof UrlTree).toBe(true);
      expect(router.serializeUrl(result as UrlTree)).toContain('/dashboard');
    });
  });
});
