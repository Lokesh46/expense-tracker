import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AccountService } from '../services/account.service';
import { AuthService } from '../services/auth.service';

/**
 * Keeps non-administrators off the admin screens.
 *
 * <p>This is a routing convenience, not a security boundary. The boundary is in
 * the API, which checks the stored role on every request — twice, in fact, at the
 * URL and again on each service method. A guard runs in the browser and could be
 * stepped around by anyone willing to open the console; what it prevents is a
 * member navigating to a page that would then fill with 403s.
 *
 * <p>The token's role decides immediately, so the common case costs no request.
 * When the token has no role claim — an older token, or one from a build that did
 * not set it — the profile is fetched and used instead, rather than assuming the
 * worse of the two answers.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const accountService = inject(AccountService);
  const router = inject(Router);

  if (!auth.getToken()) {
    return router.createUrlTree(['/login']);
  }

  if (auth.getRole() === 'ADMIN') {
    return true;
  }
  if (auth.getRole() === 'MEMBER') {
    return router.createUrlTree(['/dashboard']);
  }

  return accountService.load().pipe(
    map((account) =>
      account.role === 'ADMIN' ? true : router.createUrlTree(['/dashboard'])
    ),
    catchError(() => of(router.createUrlTree(['/dashboard'])))
  );
};
