#!/usr/bin/env node
/**
 * Runs the Spring Boot backend with the environment it needs.
 *
 * Two things this handles that a bare `mvnw spring-boot:run` does not:
 *
 *  1. JAVA_HOME. The bundled Maven wrapper needs a JDK 17+; a machine may have
 *     an older JRE first on PATH, so we locate a suitable JDK explicitly.
 *
 *  2. A Windows-only workaround. Java's NIO selector builds an internal
 *     self-pipe over an AF_UNIX socket created inside java.io.tmpdir. On some
 *     Windows machines (endpoint protection, Controlled Folder Access) binding
 *     inside %LOCALAPPDATA%\Temp is refused, and *every* Java server fails to
 *     start with "Unable to establish loopback connection". Pointing
 *     jdk.net.unixdomain.tmpdir at a repo-local directory avoids it.
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backendDir = join(repoRoot, 'apps', 'backend');
const isWindows = process.platform === 'win32';

/** Finds a JDK 17+ so the Maven wrapper does not fall back to an old JRE. */
function findJavaHome() {
  if (process.env.JAVA_HOME && existsSync(join(process.env.JAVA_HOME, 'bin', isWindows ? 'javac.exe' : 'javac'))) {
    return process.env.JAVA_HOME;
  }
  const roots = isWindows
    ? ['C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft']
    : ['/usr/lib/jvm', '/Library/Java/JavaVirtualMachines'];

  const candidates = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const entry of readdirSync(root)) {
      for (const home of [join(root, entry), join(root, entry, 'Contents', 'Home')]) {
        if (existsSync(join(home, 'bin', isWindows ? 'javac.exe' : 'javac'))) candidates.push(home);
      }
    }
  }
  // Highest version wins, so a newly installed JDK 21 beats a leftover JDK 11.
  candidates.sort((a, b) => (a.match(/\d+/g)?.join('.') ?? '').localeCompare(b.match(/\d+/g)?.join('.') ?? '', undefined, { numeric: true }));
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

const tmpDir = join(repoRoot, '.tmp');
mkdirSync(tmpDir, { recursive: true });

const jvmArgs = isWindows ? [`-Djdk.net.unixdomain.tmpdir=${tmpDir}`] : [];

const mode = process.argv.includes('--package-only')
  ? ['package', '-DskipTests']
  : process.argv.includes('--test-only')
    ? ['test']
    : ['spring-boot:run', `-Dspring-boot.run.jvmArguments=${jvmArgs.join(' ')}`];

const mvnw = isWindows ? 'mvnw.cmd' : './mvnw';

console.log(`> JAVA_HOME=${javaHome}`);
console.log(`> ${mvnw} ${mode.join(' ')}  (in apps/backend)\n`);

const child = spawn(mvnw, mode, {
  cwd: backendDir,
  stdio: 'inherit',
  shell: isWindows,
  env: { ...process.env, JAVA_HOME: javaHome, MAVEN_OPTS: jvmArgs.join(' ') },
});

child.on('exit', (code) => process.exit(code ?? 1));
