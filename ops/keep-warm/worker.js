/**
 * Keeps the free-tier API warm during waking hours.
 *
 * Render stops a free instance after ~15 minutes without traffic, and waking it
 * takes about three minutes. A request every ten minutes means the instance
 * never gets there.
 *
 * This used to run on GitHub Actions, which does not deliver high-frequency
 * schedules -- see "Keeping it warm" in the repository README. Cloudflare's
 * cron triggers do.
 */

const URL_TO_PING = 'https://expense-tracker-api-8d97.onrender.com/actuator/health';

/**
 * Long enough to confirm a warm instance answered, short enough not to sit on
 * a cold boot.
 *
 * Timing out is not a failure. The request still reached Render and started the
 * instance, which is the entire point -- the next ping ten minutes later finds
 * it up. Waiting out the full boot would only make the log prettier.
 */
const TIMEOUT_MS = 30_000;

async function ping() {
  const startedAt = Date.now();

  try {
    const response = await fetch(URL_TO_PING, {
      signal: AbortSignal.timeout(TIMEOUT_MS),
      // Render caches nothing here, but a proxy in between might, and a cached
      // 200 would keep nothing warm.
      cache: 'no-store',
    });

    const ms = Date.now() - startedAt;
    const detail = `${response.status} in ${ms}ms`;

    if (!response.ok) {
      console.error(`unhealthy: ${detail}`);
      return { ok: false, detail };
    }

    // A warm instance answers in about 250ms. Anything above a couple of
    // seconds means it had gone cold, which means a ping was missed.
    if (ms > 2_000) {
      console.warn(`answered, but was cold: ${detail}`);
    } else {
      console.log(`warm: ${detail}`);
    }

    return { ok: true, detail };
  } catch (error) {
    const ms = Date.now() - startedAt;
    const detail = `${error.name} after ${ms}ms`;
    console.warn(`no answer, boot triggered anyway: ${detail}`);
    return { ok: false, detail };
  }
}

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(ping());
  },

  /** Visiting the worker's URL runs the same check, for testing by hand. */
  async fetch(request, env, ctx) {
    const result = await ping();
    return new Response(`${result.ok ? 'ok' : 'cold or down'} — ${result.detail}\n`, {
      status: result.ok ? 200 : 503,
      headers: { 'content-type': 'text/plain; charset=utf-8' },
    });
  },
};
