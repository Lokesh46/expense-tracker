import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError } from 'rxjs/operators';
import { throwError } from 'rxjs';

import { AuthService } from '../services/auth.service';
import { Router } from '@angular/router';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.getToken();

  // If there's a token and no Authorization header, attach it.
  let outgoing = req;
  if (token && !req.headers.get('Authorization')) {
    outgoing = req.clone({ headers: req.headers.set('Authorization', `Bearer ${token}`) });
  }

  return next(outgoing).pipe(
    catchError((err) => {
      // Auto-logout and redirect on 401/403
      const status = err?.status;
      if (status === 401 || status === 403) {
        try {
          auth.logout();
          router.navigate(['/login']);
        } catch {
          // ignore navigation errors
        }
      }
      return throwError(() => err);
    })
  );
};
