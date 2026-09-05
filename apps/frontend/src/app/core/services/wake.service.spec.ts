import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { WakeService } from './wake.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';

/** Mirrors the constants in wake.service.ts. */
const COLD_THRESHOLD_MS = 2_000;
const ATTEMPT_TIMEOUT_MS = 10_000;
const RETRY_DELAY_MS = 5_000;

describe('WakeService', () => {
  let service: WakeService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(WakeService);
    http = TestBed.inject(HttpTestingController);
  });

  it('says nothing while a warm instance is answering', fakeAsync(() => {
    service.wake();
    expect(service.status()).toBe('unknown');

    // Well inside the quiet window: a warm instance replies in ~250ms.
    tick(300);
    http.expectOne('/actuator/health').flush('{"status":"UP"}');

    expect(service.status()).toBe('warm');

    // The banner must not appear after the fact once the timer would have run.
    tick(COLD_THRESHOLD_MS + 1_000);
    expect(service.status()).toBe('warm');
    http.verify();
  }));

  it('announces the wait once the instance is clearly cold, and counts it', fakeAsync(() => {
    service.wake();
    const first = http.expectOne('/actuator/health');

    tick(COLD_THRESHOLD_MS);
    expect(service.status()).toBe('waking');
    expect(service.waitedSeconds()).toBe(2);

    tick(3_000);
    expect(service.waitedSeconds()).toBe(5);

    first.flush('{"status":"UP"}');
    expect(service.status()).toBe('warm');
    http.verify();
  }));

  it('abandons a hanging attempt and asks again', fakeAsync(() => {
    service.wake();
    http.expectOne('/actuator/health'); // Left hanging: Render holds the socket open while booting.

    // The per-attempt timeout fires, then the retry delay elapses.
    tick(ATTEMPT_TIMEOUT_MS);
    tick(RETRY_DELAY_MS);

    const second = http.expectOne('/actuator/health');
    expect(service.status()).toBe('waking');

    second.flush('{"status":"UP"}');
    expect(service.status()).toBe('warm');
    http.verify();
  }));

  it('gives up eventually rather than spinning forever', fakeAsync(() => {
    service.wake();

    // 21 attempts (the first plus 20 retries), each timing out.
    for (let i = 0; i < 21; i++) {
      http.expectOne('/actuator/health');
      tick(ATTEMPT_TIMEOUT_MS);
      tick(RETRY_DELAY_MS);
    }

    expect(service.status()).toBe('unreachable');
    http.verify();
  }));

  it('does not start a second wake over a running one', fakeAsync(() => {
    service.wake();
    service.wake();

    // A second in-flight request would fail expectOne.
    const only = http.expectOne('/actuator/health');
    only.flush('{"status":"UP"}');

    // Nor does it re-check once the answer is known.
    service.wake();
    http.verify();
  }));
});
