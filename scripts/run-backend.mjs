#!/usr/bin/env node
/**
 * Runs the Spring Boot backend with the environment it needs.
 *
 * Two things this handles that a bare `mvnw spring-boot:run` does not:
 *
 *  1. JAVA_HOME. The Maven wrapper needs a JDK 17+, and a machine may well have
 *     an older JRE first on PATH, so a suitable JDK is located explicitly.
 *
 *  2. A Windows-only workaround. Java's NIO selector builds an internal
 *     self-pipe over an AF_UNIX socket created inside java.io.tmpdir. On some
 *     Windows machines — endpoint protection, or Controlled Folder Access —
 *     binding inside %LOCALAPPDATA%\Temp is refused, and then *every* Java
 *     server fails to start with "Unable to establish loopback connection".
 *     Pointing jdk.net.unixdomain.tmpdir at a directory beside the application
 *     avoids it.
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadEnvFile, missingKeys } from './load-env.mjs';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backendDir = join(repoRoot, 'apps', 'backend');
const isWindows = process.platform === 'win32';

/**
 * `--prod` runs against the real database using apps/backend/.env, which is
 * gitignored. This is for checking the production configuration before handing
 * it to a hosting provider, so a failure can be diagnosed locally rather than
 * from a deployment log.
 */
const useProdProfile = process.argv.includes('--prod');

const REQUIRED_FOR_PROD = [
  'DATABASE_URL',
  'DATABASE_USERNAME',
  'DATABASE_PASSWORD',
  'FRONTEND_URL',
];

let extraEnv = {};

if (useProdProfile) {
  const envPath = join(backendDir, '.env');

  if (!existsSync(envPath)) {
    console.error(
      `\nNo ${envPath} found.\n\n` +
        'Copy the template and fill it in:\n' +
        '  cp apps/backend/.env.example apps/backend/.env\n\n' +
        'It is gitignored, so nothing you put there can be committed.\n'
    );
    process.exit(1);
  }

  extraEnv = loadEnvFile(envPath);

  const missing = missingKeys(extraEnv, REQUIRED_FOR_PROD);
  if (missing.length > 0) {
    console.error(
      `\nStill to fill in, in apps/backend/.env:\n` +
        missing.map((key) => `  - ${key}`).join('\n') +
        '\n'
    );
    process.exit(1);
  }

  // Caught here because the JDBC driver's own message for this is obscure.
  if (!extraEnv.DATABASE_URL.startsWith('jdbc:postgresql://')) {
    console.error(
      '\nDATABASE_URL must begin with "jdbc:postgresql://".\n' +
        `  got: ${extraEnv.DATABASE_URL.split('@').pop()}\n\n` +
        'Providers show a "postgresql://user:password@host/db" URL. Add the\n' +
        'jdbc: prefix and move the credentials into DATABASE_USERNAME and\n' +
        'DATABASE_PASSWORD.\n'
    );
    process.exit(1);
  }

  if (extraEnv.DATABASE_URL.includes('@')) {
    console.error(
      '\nDATABASE_URL still contains credentials (an "@" before the host).\n' +
        'Move the user and password into DATABASE_USERNAME and\n' +
        'DATABASE_PASSWORD and remove "user:password@" from the URL.\n'
    );
    process.exit(1);
  }

  extraEnv.SPRING_PROFILES_ACTIVE = 'prod';
  // A separate port so this can run alongside the normal dev server.
  extraEnv.PORT = extraEnv.PORT || '8082';
}

/** Locates a JDK 17+ so the Maven wrapper cannot fall back to an old JRE. */
function findJavaHome() {
  const javac = isWindows ? 'javac.exe' : 'javac';

  if (process.env.JAVA_HOME && existsSync(join(process.env.JAVA_HOME, 'bin', javac))) {
    return process.env.JAVA_HOME;
  }

  // Forward slashes work on Windows too, and sidestep backslash escaping.
  const roots = isWindows
    ? [
        'C:/Program Files/Eclipse Adoptium',
        'C:/Program Files/Java',
        'C:/Program Files/Microsoft',
        'C:/Program Files/Amazon Corretto',
        'C:/Program Files/Zulu',
      ]
    : ['/usr/lib/jvm', '/Library/Java/JavaVirtualMachines', '/opt/homebrew/opt'];

  const candidates = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const entry of readdirSync(root)) {
      // macOS nests the JDK inside the bundle.
      for (const home of [join(root, entry), join(root, entry, 'Contents', 'Home')]) {
        if (existsSync(join(home, 'bin', javac))) candidates.push(home);
      }
    }
  }

  // Highest version wins, so a newly installed 21 beats a leftover 11.
  candidates.sort((a, b) =>
    (a.match(/\d+/g)?.join('.') ?? '').localeCompare(b.match(/\d+/g)?.join('.') ?? '', undefined, {
      numeric: true,
    })
  );
  return candidates.pop();
}

const javaHome = findJavaHome();
if (!javaHome) {
  console.error(
    '\nNo JDK found. Install one, then re-run:\n' +
      '  winget install EclipseAdoptium.Temurin.21.JDK        (Windows)\n' +
      '  brew install --cask temurin@21                       (macOS)\n' +
      '  sudo apt install openjdk-21-jdk                      (Debian/Ubuntu)\n'
  );
  process.exit(1);
}

/*
 * Deliberately a *relative* directory. Maven runs with its working directory
 * set to apps/backend, so ".tmp" resolves beside the application while keeping
 * the argument free of spaces — which matters because a repository path
 * containing one (as this one does) would otherwise be split by the shell and
 * by the Spring Boot plugin's own argument parser.
 */
const RELATIVE_TMP = '.tmp';
mkdirSync(join(backendDir, RELATIVE_TMP), { recursive: true });

const isPackage = process.argv.includes('--package-only');
const isTest = process.argv.includes('--test-only');

// Only a running server opens a selector, so the workaround is not needed to
// compile or to run the tests, which use MockMvc rather than a real port.
const mode = isPackage
  ? ['package', '-DskipTests']
  : isTest
    ? ['test']
    : [
        'spring-boot:run',
        ...(isWindows
          ? [`-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=${RELATIVE_TMP}`]
          : []),
      ];

/*
 * Launching the wrapper on Windows is fussier than it looks: cmd.exe will not
 * resolve a command from the working directory, a .cmd file needs a shell at
 * all, and the shell splits the command on spaces — so the absolute path is
 * quoted for it.
 */
const mvnw = join(backendDir, isWindows ? 'mvnw.cmd' : 'mvnw');
const command = isWindows && mvnw.includes(' ') ? `"${mvnw}"` : mvnw;

console.log(`> JAVA_HOME=${javaHome}`);
if (useProdProfile) {
  // The host is echoed to confirm which database is being reached; the
  // credentials never are.
  const host = extraEnv.DATABASE_URL.replace('jdbc:postgresql://', '').split('/')[0];
  console.log(`> profile=prod  db=${host}  port=${extraEnv.PORT}`);
}
console.log(`> mvnw ${mode.join(' ')}  (in apps/backend)\n`);

const child = spawn(command, mode, {
  cwd: backendDir,
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, ...extraEnv, JAVA_HOME: javaHome },
});

child.on('exit', (code) => process.exit(code ?? 1));
