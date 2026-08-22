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

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backendDir = join(repoRoot, 'apps', 'backend');
const isWindows = process.platform === 'win32';

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
console.log(`> mvnw ${mode.join(' ')}  (in apps/backend)\n`);

const child = spawn(command, mode, {
  cwd: backendDir,
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, JAVA_HOME: javaHome },
});

child.on('exit', (code) => process.exit(code ?? 1));
