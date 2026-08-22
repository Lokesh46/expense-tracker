/**
 * Production settings.
 *
 * Point `apiBaseUrl` at the deployed Spring Boot service. The previous host
 * (expense-tracker-backend-wvri.onrender.com) no longer exists, so this must be
 * updated when the backend is redeployed.
 */
export const environment = {
  production: true,
  apiBaseUrl: 'https://expense-tracker-backend-wvri.onrender.com',
} as const;
