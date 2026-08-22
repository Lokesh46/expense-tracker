import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AuthService } from './auth.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';

/** Builds a token whose payload decodes to the given claims. */
function fakeJwt(claims: Record<string, unknown>): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`;
}

const inOneHour = () => Math.floor(Date.now() / 1000) + 3600;
const anHourAgo = () => Math.floor(Date.now() / 1000) - 3600;

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  function configure(): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    if (http) {
      http.verify();
    }
    localStorage.clear();
    sessionStorage.clear();
  });

  it('keeps the token in localStorage when asked to be remembered', () => {
    configure();
    const token = fakeJwt({ sub: 'ada', exp: inOneHour() });

    service.authenticate({ username: 'ada', password: 'x' }, { rememberMe: true }).subscribe();
    http.expectOne('/authenticate').flush({ token });

    expect(localStorage.getItem('auth_token')).toBe(token);
    expect(sessionStorage.getItem('auth_token')).toBeNull();
  });

  it('keeps the token only for the session when not remembered', () => {
    configure();
    const token = fakeJwt({ sub: 'ada', exp: inOneHour() });

    service.authenticate({ username: 'ada', password: 'x' }, { rememberMe: false }).subscribe();
    http.expectOne('/authenticate').flush({ token });

    expect(sessionStorage.getItem('auth_token')).toBe(token);
    expect(localStorage.getItem('auth_token')).toBeNull();
  });

  it('does not leave a copy behind when the choice changes', () => {
    configure();
    sessionStorage.setItem('auth_token', 'stale');

    const token = fakeJwt({ sub: 'ada', exp: inOneHour() });
    service.authenticate({ username: 'ada', password: 'x' }, { rememberMe: true }).subscribe();
    http.expectOne('/authenticate').flush({ token });

    // A leftover token in the other store would outlive an explicit sign-out.
    expect(sessionStorage.getItem('auth_token')).toBeNull();
  });

  it('reports the signed-in state to subscribers', () => {
    configure();
    const states: boolean[] = [];
    service.isAuthenticated$.subscribe((value) => states.push(value));

    service.authenticate({ username: 'ada', password: 'x' }).subscribe();
    http.expectOne('/authenticate').flush({ token: fakeJwt({ sub: 'ada', exp: inOneHour() }) });

    service.logout();

    expect(states).toEqual([false, true, false]);
  });

  it('reads the username from the token', () => {
    localStorage.setItem('auth_token', fakeJwt({ sub: 'grace', exp: inOneHour() }));
    configure();

    expect(service.getUsername()).toBe('grace');
  });

  it('discards an already-expired token on startup', () => {
    // Rendering as signed in and then failing every request with a 401 is worse
    // than simply asking the user to sign in again.
    localStorage.setItem('auth_token', fakeJwt({ sub: 'ada', exp: anHourAgo() }));
    configure();

    expect(service.getToken()).toBeNull();
  });

  it('keeps a token that has not expired', () => {
    localStorage.setItem('auth_token', fakeJwt({ sub: 'ada', exp: inOneHour() }));
    configure();

    expect(service.getToken()).not.toBeNull();
  });

  it('treats a malformed token as no token', () => {
    localStorage.setItem('auth_token', 'not-a-jwt');
    configure();

    expect(service.getToken()).toBeNull();
    expect(service.getUsername()).toBeNull();
  });

  it('clears both stores on sign-out', () => {
    configure();
    localStorage.setItem('auth_token', 'a');
    sessionStorage.setItem('auth_token', 'b');

    service.logout();

    expect(localStorage.getItem('auth_token')).toBeNull();
    expect(sessionStorage.getItem('auth_token')).toBeNull();
  });

  it('exposes when the session expires', () => {
    const exp = inOneHour();
    localStorage.setItem('auth_token', fakeJwt({ sub: 'ada', exp }));
    configure();

    expect(service.getExpiry()?.getTime()).toBe(exp * 1000);
  });
});
