import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const root = fileURLToPath(new URL('../', import.meta.url));
export const windows = process.platform === 'win32';

// Only fixed executable names/arguments supplied by repository scripts, never user input.
export function command(executable, args, cwd = root) {
  if (windows && /\.(cmd|bat)$/.test(executable)) {
    // Resolve before quoting: invoking a bare quoted batch name can give %~dp0 the wrong directory.
    const located = spawnSync('where.exe', [executable], { cwd, encoding: 'utf8', windowsHide: true });
    if (located.status !== 0) throw new Error(`Cannot locate ${executable}`);
    const absolute = located.stdout.trim().split(/\r?\n/)[0];
    return ['cmd.exe', ['/d', '/s', '/c', `"${[absolute, ...args].map((part) => `"${part}"`).join(' ')}"`]];
  }
  return [executable, args];
}

export function run(executable, args, options = {}) {
  const [program, parameters] = command(executable, args, options.cwd);
  const result = spawnSync(program, parameters, { cwd: root, stdio: 'inherit', windowsVerbatimArguments: windows && program === 'cmd.exe', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${executable} failed (exit ${result.status})`);
  return result;
}

export function maven(args, options = {}) {
  return run(windows ? 'mvnw.cmd' : './mvnw', ['-B', '-ntp', ...args], {
    cwd: fileURLToPath(new URL('../backend/server/', import.meta.url)), ...options,
  });
}
