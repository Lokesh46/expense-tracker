import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';

/**
 * Keeps a signed-in user off the sign-in and register screens; landing there
 * with a live session and being asked to log in again is disorienting.
 */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.getToken() ? router.createUrlTree(['/dashboard']) : true;
};
