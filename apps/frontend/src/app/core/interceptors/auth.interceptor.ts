import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';
import { describeError } from '../utils/api-error';

/** Endpoints where a 401 is the answer to a question, not a dead session. */
const SIGN_IN_PATHS = ['/authenticate', '/register'];

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const notifications = inject(NotificationService);

  const token = auth.getToken();

  let outgoing = req;
  if (token && !req.headers.get('Authorization')) {
    outgoing = req.clone({ headers: req.headers.set('Authorization', `Bearer ${token}`) });
  }

  return next(outgoing).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && shouldEndSession(error)) {
        auth.logout();

        // The backend explains *why* the session ended — suspended, locked,
        // password changed elsewhere, revoked by an administrator. Dropping that
        // and redirecting silently is how a suspended user concludes the
        // application is broken.
        notifications.showWarning(describeError(error, 'Your session has ended. Please sign in again.'));
        router.navigate(['/login']);
      }

      return throwError(() => error);
    })
  );
};

/**
 * Only a 401 ends the session, and only away from the sign-in endpoints.
 *
 * <p>A 403 used to end it too, which was wrong and became visibly wrong once
 * there were endpoints a signed-in member is legitimately refused: opening an
 * admin URL as a member would have signed them out of the application. A 403
 * means "not you", not "not anymore".
 *
 * <p>A 401 from {@code /authenticate} is a wrong password. Logging out over it
 * would clear a session the user may still have had in another tab.
 */
function shouldEndSession(error: HttpErrorResponse): boolean {
  if (error.status !== 401) {
    return false;
  }
  return !SIGN_IN_PATHS.some((path) => error.url?.endsWith(path));
}
