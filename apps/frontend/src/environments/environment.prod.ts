/**
 * Production settings.
 *
 * The API runs on Render's free plan, which spins the instance down after
 * roughly 15 minutes of inactivity. The first request after a quiet spell can
 * therefore take around 50 seconds while it wakes; every request after that is
 * normal speed.
 */
export const environment = {
  production: true,
  apiBaseUrl: 'https://expense-tracker-api-8d97.onrender.com',
} as const;
