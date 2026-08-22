/**
 * Reads `apps/backend/.env` into a plain object.
 *
 * Spring Boot has no notion of a .env file, so this exists to keep secrets in
 * one gitignored place rather than pasted into a shell each time — which is how
 * they end up in shell history, or in a screenshot.
 *
 * Deliberately minimal: no interpolation, no shell semantics. A value is
 * whatever follows the first `=`, so a password containing `#`, `$` or a space
 * survives intact.
 */
import { existsSync, readFileSync } from 'node:fs';

export function loadEnvFile(path) {
  if (!existsSync(path)) {
    return {};
  }

  const env = {};

  for (const rawLine of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();

    // Blank lines and comments.
    if (!line || line.startsWith('#')) {
      continue;
    }

    const separator = line.indexOf('=');
    if (separator === -1) {
      continue;
    }

    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();

    // Strip one layer of matching quotes, so a value can be quoted if it has
    // leading or trailing spaces that matter.
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))) {
      value = value.slice(1, -1);
    }

    if (key) {
      env[key] = value;
    }
  }

  return env;
}

/**
 * Reports which of the required keys are missing, so a misconfiguration is a
 * clear message rather than a stack trace from the JDBC driver.
 */
export function missingKeys(env, required) {
  return required.filter((key) => !env[key] || env[key].startsWith('<'));
}
