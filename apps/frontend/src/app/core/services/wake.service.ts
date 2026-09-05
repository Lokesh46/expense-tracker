import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { retry, timeout } from 'rxjs/operators';

import { API_BASE_URL } from '../tokens/api-base-url.token';

export type WakeState = 'unknown' | 'warm' | 'waking' | 'unreachable';

/**
 * Below this, the API answered quickly enough that the user should never be
 * told anything. A warm instance replies in about a quarter of a second.
 */
const COLD_THRESHOLD_MS = 2_000;

/**
 * How long one attempt is given. Render holds the socket open while the
 * instance boots, so a request that hangs tells us nothing and cannot report
 * progress — cancelling and asking again is what turns the wait into something
 * the screen can count.
 */
const ATTEMPT_TIMEOUT_MS = 10_000;

const RETRY_DELAY_MS = 5_000;

/** 20 x (10s + 5s) = five minutes, comfortably past a measured cold start. */
const MAX_RETRIES = 20;

/**
 * Nudges the API awake before the user needs it.
 *
 * <p>The API runs on Render's free plan, which stops the instance after roughly
 * 15 minutes without traffic. Waking it takes about three minutes — measured,
 * not estimated — because a cold request pays for the container, Spring Boot's
 * startup and Neon resuming its own compute, all on a throttled CPU.
 *
 * <p>Sign-in is where that hurts most: the user types a password, presses a
 * button, and watches nothing happen for long enough to conclude the app is
 * broken. Asking for the health endpoint the moment the sign-in screen renders
 * spends that boot while they are still typing, and leaves the screen something
 * honest to say when they are quicker than the server.
 *
 * <p>This narrows the window; it does not close it. The scheduled ping that
 * keeps the instance warm is the thing that stops the wait happening at all.
 */
@Injectable({ providedIn: 'root' })
export class WakeService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private readonly state = signal<WakeState>('unknown');
  private readonly elapsed = signal(0);

  /** What the API is currently believed to be doing. */
  readonly status = this.state.asReadonly();

  /** Whole seconds spent waiting, for a screen that would rather not lie. */
  readonly waitedSeconds = this.elapsed.asReadonly();

  private inFlight = false;
  private ticker?: Subscription;

  /**
   * Asks the API to wake, and keeps asking until it does.
   *
   * <p>Safe to call more than once: a wake already running, or already
   * finished, is left alone.
   */
  wake(): void {
    if (this.inFlight || this.state() === 'warm') {
      return;
    }
    this.inFlight = true;

    const startedAt = Date.now();

    // Silence for the first couple of seconds. A warm instance answers inside
    // that, and a banner that flashes up on every visit teaches the user to
    // ignore it on the visit that matters.
    this.ticker = timer(COLD_THRESHOLD_MS, 1_000).subscribe(() => {
      if (this.state() === 'unknown') {
        this.state.set('waking');
      }
      this.elapsed.set(Math.round((Date.now() - startedAt) / 1_000));
    });

    this.http
      .get(`${this.baseUrl}/actuator/health`, { responseType: 'text' })
      .pipe(
        // Applies per attempt: retry resubscribes to the whole chain.
        timeout(ATTEMPT_TIMEOUT_MS),
        retry({ count: MAX_RETRIES, delay: () => timer(RETRY_DELAY_MS) })
      )
      .subscribe({
        next: () => this.settle('warm'),
        error: () => this.settle('unreachable'),
      });
  }

  private settle(state: WakeState): void {
    this.ticker?.unsubscribe();
    this.ticker = undefined;
    this.inFlight = false;
    this.state.set(state);
  }
}
