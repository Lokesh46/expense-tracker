/**
 * Local development settings.
 *
 * `ng serve` proxies /api, /authenticate and /register to the Spring Boot app
 * on port 8081 (see proxy.conf.json), so requests stay same-origin and CORS
 * never enters the picture during development.
 */
export const environment = {
  production: false,
  apiBaseUrl: '',
} as const;
