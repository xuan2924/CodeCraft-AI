import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { root, run, maven, windows, command } from './process.mjs';
import { quickBudget, budgetStatus } from './verification-budget.mjs';

const mode = process.argv[2] ?? '--quick';
if (process.argv.length > 3 || !['--quick', '--all', '--heavy'].includes(mode)) {
  throw new Error('Usage: node scripts/verify.mjs [--quick|--all|--heavy]');
}
const budget = quickBudget(mode === '--quick' ? process.env : {});
const npm = windows ? 'npm.cmd' : 'npm';
const go = { cwd: join(root, 'backend/sandbox') };
const frontend = { cwd: join(root, 'frontend') };
// Independent languages run concurrently; each tool owns its own build directory.
async function check(label, executable, args, cwd = root, env = process.env) {
  const started = performance.now();
  const [program, parameters] = command(executable, args, cwd);
  await new Promise((resolve, reject) => {
    const child = spawn(program, parameters, { cwd, env, stdio: 'inherit', detached: !windows,
      windowsVerbatimArguments: windows && program === 'cmd.exe' });
    let timedOut = false;
    const timer = mode === '--quick' ? setTimeout(() => {
      timedOut = true;
      console.error(`${label}: ${budget.timeoutSeconds}s hard timeout; terminating owned process tree`);
      if (windows) {
        const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
        killer.once('error', () => child.kill());
      } else {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
      }
    }, Math.max(1, budget.timeoutSeconds * 1000 - (performance.now() - verificationStarted))) : undefined;
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
    child.once('exit', (code) => {
      clearTimeout(timer);
      if (timedOut) reject(new Error(`${label} exceeded the ${budget.timeoutSeconds}s hard timeout`));
      else code === 0 ? resolve() : reject(new Error(`${label} failed (${code})`));
    });
  });
  console.log(`${label}: ${((performance.now() - started) / 1000).toFixed(2)}s`);
}
const verificationStarted = performance.now();
const checks = await Promise.allSettled([
  check('Node contracts', process.execPath, ['--test', 'scripts/problem-contract.test.mjs', 'scripts/publication-contract.test.mjs', 'scripts/verification-budget.test.mjs', 'scripts/docker-images.test.mjs', 'scripts/model-config.test.mjs']),
  check('Java lint + quick behavior', windows ? 'mvnw.cmd' : './mvnw', ['-B', '-ntp', 'test'], join(root, 'backend/server')),
  check('Go quick behavior + vet', 'go', ['test', '-count=1', './internal/status', './internal/execution', './internal/security'], go.cwd),
  check('Linux Go compile + vet', 'go', ['vet', './...'], go.cwd, { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' }),
  check('Vue typecheck', npm, ['run', 'typecheck'], frontend.cwd),
  check('Vue lint', npm, ['run', 'lint'], frontend.cwd),
  check('Vue unit/components', npm, ['run', 'test:unit'], frontend.cwd),
]);
const failed = checks.filter((result) => result.status === 'rejected').map((result) => result.reason);
if (failed.length) throw new AggregateError(failed, 'Quick verification failed');
const formatted = run('gofmt', ['-l', 'cmd', 'internal'], { ...go, encoding: 'utf8', stdio: 'pipe' });
if (formatted.stdout.trim()) throw new Error(`Run gofmt on:\n${formatted.stdout}`);
const seconds = (performance.now() - verificationStarted) / 1000;
console.log(`Quick verification: ${seconds.toFixed(2)}s (target ${budget.targetSeconds}s${mode === '--quick' ? `; hard timeout ${budget.timeoutSeconds}s` : '; no quick hard timeout in full mode'}).`);
if (mode === '--quick') {
  const status = budgetStatus(seconds, budget);
  if (status === 'timeout') throw new Error(`Quick verification exceeded the ${budget.timeoutSeconds}s hard timeout; inspect the timings above.`);
  if (status === 'warning') console.warn(`Warning: quick verification exceeded the ${budget.targetSeconds}s performance target, but remains within the ${budget.timeoutSeconds}s hard timeout. Check machine load, caches and per-group timings.`);
} else {
  maven(['verify']);
  run('go', ['mod', 'verify'], go);
  run('go', ['vet', './...'], go);
  run('go', ['test', '-count=1', './...'], go);
  // Full Go race detector requires CGO/Linux (containers run Java & Go under Docker; Windows host does not bundle llvm-mingw).
  run('go', ['test', '-race', '-count=1', './...'], go);
  run('go', ['build', './cmd/sandbox'], go);
  run('go', ['vet', './...'], { ...go, env: { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' } });
  run(process.execPath, ['--test', 'scripts/validation-cleanup.docker.test.mjs']);
  run(process.execPath, ['scripts/test-workspace.mjs']);
  run(process.execPath, ['scripts/test-execution.mjs']);
  run(npm, ['run', 'build'], frontend);
  run(npm, ['audit'], frontend);
  run(npm, ['run', 'test:integration'], frontend);
  for (const suite of [[], ['--identity'], ['--workspace']]) run(process.execPath, ['scripts/test-tracer.mjs', ...suite]);
  console.log('All verification passed.');
}
