import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.getToken()) {
    return true;
  }

  // Returning a UrlTree rather than navigating imperatively lets the router
  // cancel this navigation cleanly, and remembering where the user was heading
  // means a deep link still works after signing in.
  return router.createUrlTree(['/login'], {
    queryParams: state.url === '/dashboard' ? {} : { returnUrl: state.url },
  });
};
