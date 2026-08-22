import { HttpErrorResponse } from '@angular/common/http';

import { describeError } from './api-error';

describe('describeError', () => {
  it("prefers the backend's own message", () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { message: '"Groceries" is used by 3 transactions.' },
    });

    expect(describeError(error)).toBe('"Groceries" is used by 3 transactions.');
  });

  it('leads with field errors, which are more specific than the summary', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: {
        message: 'Some fields need attention',
        fields: { amount: 'Amount must be greater than zero' },
      },
    });

    expect(describeError(error)).toBe('Amount must be greater than zero');
  });

  it('joins several field errors', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { fields: { username: 'Too short', password: 'Too short' } },
    });

    expect(describeError(error)).toBe('Too short Too short');
  });

  it('explains a connection failure rather than blaming the request', () => {
    // status 0 means the request never reached a server at all, which almost
    // always means the API is not running.
    const error = new HttpErrorResponse({ status: 0 });

    expect(describeError(error)).toContain('Cannot reach the server');
  });

  it('uses a plain-text error body when that is all there is', () => {
    const error = new HttpErrorResponse({ status: 500, error: 'Internal failure' });
    expect(describeError(error)).toBe('Internal failure');
  });

  it('falls back when the body carries nothing useful', () => {
    const error = new HttpErrorResponse({ status: 500, error: {} });
    expect(describeError(error, 'Could not save.')).toBe('Could not save.');
  });

  it('falls back for something that is not an HTTP error at all', () => {
    expect(describeError(new Error('boom'), 'Could not save.')).toBe('Could not save.');
    expect(describeError(null, 'Could not save.')).toBe('Could not save.');
  });

  it('ignores an empty message in favour of the fallback', () => {
    const error = new HttpErrorResponse({ status: 500, error: '   ' });
    expect(describeError(error, 'Could not save.')).toBe('Could not save.');
  });
});
