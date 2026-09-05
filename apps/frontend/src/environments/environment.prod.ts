/**
 * Production settings.
 *
 * The API runs on Render's free plan, which stops the instance after roughly
 * 15 minutes of inactivity. Waking it again takes about three minutes, measured
 * end to end: the container, Spring Boot's startup and Neon resuming its own
 * compute, all on a throttled CPU. Every request after that is normal speed.
 *
 * `WakeService` starts that boot as soon as an auth screen renders, so the wait
 * overlaps with the form being filled in rather than following it.
 */
export const environment = {
  production: true,
  apiBaseUrl: 'https://expense-tracker-api-8d97.onrender.com',
} as const;
