import { HttpErrorResponse } from '@angular/common/http';

/** The error body produced by the backend's GlobalExceptionHandler. */
interface ApiErrorBody {
  message?: string;
  fields?: Record<string, string>;
}

/**
 * Turns a failed request into something worth showing a user.
 *
 * The backend sends a readable `message` for everything it handles, so that is
 * preferred; the fallbacks cover the cases it never sees — the network being
 * down, or the API not running at all.
 */
export function describeError(error: unknown, fallback = 'Something went wrong.'): string {
  if (!(error instanceof HttpErrorResponse)) {
    return fallback;
  }

  // status 0 means the request never reached a server.
  if (error.status === 0) {
    return 'Cannot reach the server. Is the API running?';
  }

  const body = error.error as ApiErrorBody | string | null;

  if (typeof body === 'string' && body.trim()) {
    return body;
  }

  if (body && typeof body === 'object') {
    // Field errors are more specific than the generic summary, so lead with them.
    if (body.fields) {
      const messages = Object.values(body.fields).filter(Boolean);
      if (messages.length) {
        return messages.join(' ');
      }
    }
    if (body.message) {
      return body.message;
    }
  }

  return fallback;
}
