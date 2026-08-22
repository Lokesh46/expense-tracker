#!/usr/bin/env node
/**
 * Turns a provider's Postgres connection string into the three values Spring
 * Boot needs, and writes them to apps/backend/.env.
 *
 *   npm run setup:db
 *
 * The string is read from stdin rather than taken as an argument, so the
 * password does not end up in shell history.
 */
import { createInterface } from 'node:readline';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadEnvFile } from './load-env.mjs';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const envPath = join(repoRoot, 'apps', 'backend', '.env');

/**
 * Parameters the PostgreSQL JDBC driver understands. Providers add their own
 * (Neon appends channel_binding, for instance) and carrying them across risks
 * an obscure connection failure, so anything unrecognised is dropped and
 * reported rather than passed through.
 */
const JDBC_SAFE_PARAMS = new Set(['sslmode', 'application_name', 'connect_timeout', 'options']);

function fail(message) {
  console.error(`\n${message}\n`);
  process.exit(1);
}

function convert(raw) {
  const input = raw.trim().replace(/^["']|["']$/g, '');

  if (!input) {
    fail('Nothing entered.');
  }

  if (input.startsWith('jdbc:')) {
    fail('That is already a JDBC URL. Paste the provider\'s original postgresql:// string.');
  }

  if (!/^postgres(ql)?:\/\//i.test(input)) {
    fail(
      'That does not look like a Postgres connection string.\n' +
        'Expected something starting postgresql:// — Neon shows it under "Connection string".'
    );
  }

  let url;
  try {
    url = new URL(input);
  } catch {
    fail('Could not parse that as a URL. Copy the whole string, including postgresql://');
  }

  // Credentials arrive percent-encoded, so a password containing @ or / works.
  const username = decodeURIComponent(url.username || '');
  const password = decodeURIComponent(url.password || '');
  const database = url.pathname.replace(/^\//, '');

  if (!username || !password) {
    fail(
      'No credentials found in that string.\n' +
        'Neon sometimes shows a URL without them — pick the variant that includes\n' +
        'user:password@, or copy the password separately from the dashboard.'
    );
  }
  if (!database) {
    fail('No database name found after the host.');
  }

  const host = url.host;

  const kept = [];
  const dropped = [];
  for (const [key, value] of url.searchParams) {
    (JDBC_SAFE_PARAMS.has(key.toLowerCase()) ? kept : dropped).push([key, value]);
  }

  // Neon requires TLS; without this the connection is refused or hangs.
  if (!kept.some(([key]) => key.toLowerCase() === 'sslmode')) {
    kept.push(['sslmode', 'require']);
  }

  const query = kept.map(([key, value]) => `${key}=${value}`).join('&');

  return {
    jdbcUrl: `jdbc:postgresql://${host}/${database}?${query}`,
    username,
    password,
    host,
    dropped,
    pooled: /-pooler\./.test(host),
  };
}

function write(result) {
  // Anything already set — FRONTEND_URL especially — is preserved.
  const existing = existsSync(envPath) ? loadEnvFile(envPath) : {};

  const merged = {
    ...existing,
    DATABASE_URL: result.jdbcUrl,
    DATABASE_USERNAME: result.username,
    DATABASE_PASSWORD: result.password,
    FRONTEND_URL:
      existing.FRONTEND_URL && !existing.FRONTEND_URL.startsWith('<')
        ? existing.FRONTEND_URL
        : 'http://localhost:4300',
  };

  const body =
    '# Written by scripts/setup-db-env.mjs. Gitignored — keep it that way.\n' +
    Object.entries(merged)
      .filter(([, value]) => value && !String(value).startsWith('<'))
      .map(([key, value]) => `${key}=${value}`)
      .join('\n') +
    '\n';

  writeFileSync(envPath, body, { encoding: 'utf8' });
}

const rl = createInterface({ input: process.stdin, output: process.stdout });

console.log('\nPaste the connection string from your provider.');
console.log('It looks like: postgresql://user:password@host/dbname?sslmode=require\n');

rl.question('Connection string: ', (answer) => {
  rl.close();

  const result = convert(answer);
  write(result);

  console.log('\nWritten to apps/backend/.env\n');
  console.log(`  DATABASE_URL      = ${result.jdbcUrl}`);
  console.log(`  DATABASE_USERNAME = ${result.username}`);
  console.log(`  DATABASE_PASSWORD = ${'*'.repeat(Math.min(result.password.length, 24))}`);

  if (result.dropped.length > 0) {
    console.log(
      `\nDropped ${result.dropped.map(([key]) => key).join(', ')} — the JDBC driver does ` +
        'not use these.'
    );
  }

  if (result.pooled) {
    console.log(
      '\nWarning: that is a pooled host (-pooler). Spring Boot runs its own\n' +
        'connection pool, and stacking PgBouncer on top of it breaks prepared\n' +
        'statements. Use the direct connection string instead.'
    );
  }

  console.log('\nNow check it reaches the database:\n\n  npm run dev:api:prod\n');
});
