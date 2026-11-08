import { spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { openSync, closeSync } from 'node:fs';
import { createServer } from 'node:net';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { root, run, maven, windows } from './process.mjs';
import { serviceEnv } from './local-config.mjs';

// Never consumes the development .env, database, volume, or running services.
const project = `cc-tracer-${process.pid}-${randomBytes(4).toString('hex')}`;
const directory = join(root, '.runtime', project);
await mkdir(directory, { recursive: true });
const children = [];
const reservations = [];
async function reservePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  reservations.push(server);
  return String(server.address().port);
}
const values = {
  POSTGRES_USER: 'tracer_test', POSTGRES_DB: 'tracer_test',
  POSTGRES_PASSWORD: randomBytes(32).toString('hex'),
  POSTGRES_PORT: await reservePort(), JAVA_PORT: await reservePort(),
  SANDBOX_PORT: await reservePort(), WEB_PORT: await reservePort(),
};
const envFile = join(directory, '.env');
await writeFile(envFile, Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n'), { mode: 0o600 });
await Promise.all(reservations.map((server) => new Promise((resolve) => server.close(resolve))));
const composeArgs = ['compose', '-p', project, '--env-file', envFile, '-f', join(root, 'compose.yaml')];
const composeEnv = serviceEnv(values, 'postgres');
function compose(args) { run('docker', [...composeArgs, ...args], { env: composeEnv }); }
function start(name, executable, args, env) {
  const log = openSync(join(directory, `${name}.log`), 'a');
  const child = spawn(executable, args, { cwd: root, env, stdio: ['ignore', log, log], detached: !windows });
  closeSync(log);
  child.on('error', (error) => { child.startError = error; });
  children.push(child);
  return child;
}
async function stop(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null || child.startError) return;
  const exited = new Promise((resolve) => child.once('exit', resolve));
  if (windows) {
    const killed = spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    if (killed.status !== 0 && child.exitCode === null) throw new Error(`Unable to stop owned process ${child.pid}`);
  } else {
    process.kill(-child.pid, 'SIGTERM');
  }
  let timer;
  try {
    await Promise.race([exited, new Promise((_, reject) => {
      timer = setTimeout(() => {
        if (!windows) { try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already exited */ } }
        reject(new Error(`Timed out stopping owned process ${child.pid}`));
      }, 10000);
    })]);
  } finally { clearTimeout(timer); }
}
async function waitFor(url, expectedStatus, child) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (child && (child.startError || child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`Service exited before readiness; inspect ${directory}`);
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(7000) });
      await response.arrayBuffer();
      if (response.status === expectedStatus) return;
    } catch { /* bounded readiness polling */ }
    await delay(500);
  }
  throw new Error(`Readiness timed out; inspect ${directory}`);
}
const javaUrl = `http://127.0.0.1:${values.JAVA_PORT}`;
const webUrl = `http://127.0.0.1:${values.WEB_PORT}`;
function sandbox() {
  return start('sandbox', join(root, 'backend/sandbox', windows ? 'sandbox.exe' : 'sandbox'), [], serviceEnv(values, 'sandbox'));
}
function java() {
  return start('java', 'java', ['-jar', join(root, 'backend/server/target/server-0.1.0-SNAPSHOT.jar')], serviceEnv(values, 'java'));
}
function browser(database = 'AVAILABLE', sandboxState = 'AVAILABLE', javaState = 'AVAILABLE') {
  run(process.execPath, [join(root, 'frontend/node_modules/@playwright/test/cli.js'), 'test'], {
    cwd: join(root, 'frontend'),
    env: { ...serviceEnv(values, 'web'), TRACER_WEB_URL: webUrl, TRACER_SCREENSHOT_DIR: directory,
      EXPECT_DATABASE: database, EXPECT_SANDBOX: sandboxState, EXPECT_JAVA: javaState },
  });
}
let failure;
try {
  run('docker', ['info'], { stdio: 'ignore' });
  // Fail fast if built artifacts or matching browser are missing; see README setup.
  compose(['up', '-d', '--wait', 'postgres']);
  let goProcess = sandbox();
  let javaProcess = java();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  maven(['failsafe:integration-test', 'failsafe:verify'], { env: { ...process.env, TRACER_IT_URL: javaUrl } });
  const webProcess = start('web', process.execPath,
    [join(root, 'frontend/node_modules/vite/bin/vite.js'), 'preview', join(root, 'frontend'), '--config', join(root, 'frontend/vite.config.ts')],
    serviceEnv(values, 'web'));
  // Vite's root is explicitly supplied because this process starts in the repository root.
  await waitFor(webUrl, 200, webProcess);
  console.log('Browser: all dependencies available');
  browser();
  await stop(goProcess);
  await waitFor(`${javaUrl}/api/internal/status`, 503, javaProcess);
  console.log('Browser: Go stopped');
  browser('AVAILABLE', 'UNAVAILABLE');
  goProcess = sandbox();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  compose(['stop', 'postgres']);
  await waitFor(`${javaUrl}/api/internal/status`, 503, javaProcess);
  console.log('Browser: database stopped');
  browser('UNAVAILABLE', 'AVAILABLE');
  compose(['start', '--wait', 'postgres']);
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  await stop(javaProcess);
  javaProcess = java();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  console.log('Browser: restart and migration revalidation');
  browser();
  await stop(javaProcess);
  console.log('Browser: Java stopped');
  browser('UNAVAILABLE', 'UNAVAILABLE', 'UNAVAILABLE');
  compose(['stop', 'postgres']);
  console.log('Startup: database/migration unavailable must fail closed');
  const failedStartup = java();
  const startupDeadline = Date.now() + 30000;
  while (failedStartup.exitCode === null && !failedStartup.startError && Date.now() < startupDeadline) await delay(250);
  if (failedStartup.startError || failedStartup.exitCode === null || failedStartup.exitCode === 0) {
    throw new Error('Expected Java to exit nonzero when its migration database is unavailable');
  }
} catch (error) {
  failure = error;
} finally {
  for (const child of children.reverse()) {
    try { await stop(child); } catch (error) { failure ??= error; }
  }
  try { compose(['down', '--volumes', '--remove-orphans']); } catch (error) { failure ??= error; }
  await rm(envFile, { force: true });
}
if (failure) throw failure;
console.log(`Real tracer suite passed. Logs: ${directory}`);
