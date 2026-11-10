import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { openSync, closeSync } from 'node:fs';
import { createServer } from 'node:net';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { root, run, windows } from './process.mjs';
import { serviceEnv } from './local-config.mjs';
import { buildSandboxImage } from './build-sandbox.mjs';
import { buildJavaImage, javaComposeEnv } from './build-java.mjs';
import { linuxJavaTests } from './linux-java-tests.mjs';
import { browserFixtures } from './test-support/browser-fixtures.mjs';

const smoke = process.argv.includes('--smoke');
const identityMode = process.argv.includes('--identity');
const workspaceMode = process.argv.includes('--workspace');
if (smoke && !identityMode && !workspaceMode) throw new Error('--smoke requires --identity and/or --workspace');
if (smoke && process.argv.includes('--boundary-only')) throw new Error('--smoke cannot run boundary suites');
const boundaryOnly = process.argv.includes('--boundary-only');
if (boundaryOnly && !workspaceMode && !identityMode) throw new Error('--boundary-only requires --identity or --workspace');
const workspaceTestOption = process.argv.find((argument) => argument.startsWith('--workspace-test='));
const workspaceTest = workspaceTestOption?.slice('--workspace-test='.length);
if (workspaceTestOption !== undefined && (!workspaceMode || !boundaryOnly || !/^[A-Za-z][A-Za-z0-9]*(?:\+[A-Za-z][A-Za-z0-9]*)*$/.test(workspaceTest))) {
  throw new Error('--workspace-test requires --workspace --boundary-only and method names joined by +');
}
const recoveryOnly = process.argv.includes('--workspace-recovery-only');
if (recoveryOnly && (!workspaceMode || !boundaryOnly || workspaceTest)) {
  throw new Error('--workspace-recovery-only requires --workspace --boundary-only without --workspace-test');
}
const browserTest = process.argv.find((argument) => argument.startsWith('--browser-test='))?.slice('--browser-test='.length);
if (browserTest !== undefined && (!smoke || boundaryOnly || !browserTest || browserTest.length > 256)) throw new Error('Invalid --browser-test selection');
const updatedIdentityPassword = randomBytes(32).toString('hex');
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
  BOOTSTRAP_ADMIN_USERNAME: 'tracer_admin', BOOTSTRAP_ADMIN_PASSWORD: randomBytes(32).toString('hex'),
  IDENTITY_FINGERPRINT_KEY: randomBytes(32).toString('hex'),
  ...(workspaceMode ? { SANDBOX_RPC_KEY: randomBytes(32).toString('hex'),
    PROBLEM_PUBLICATION_FILE: join(directory, 'problem-publication.json') } : {}),
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
  if (child?.sandboxContainer) {
    compose(['stop', 'sandbox']);
    child.sandboxContainer = false;
  }
  if (child?.javaContainer) {
    compose(['stop', 'java']);
    child.javaContainer = false;
  }
  if (!child || child.exitCode !== null || child.signalCode !== null || child.startError) return;
  const exited = new Promise((resolve) => child.once('exit', resolve));
  if (windows) {
    // Compose may have exited during its synchronous container stop; Node has not
    // necessarily delivered the exit event yet. The bounded wait below is authoritative.
    spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    try { process.kill(-child.pid, 'SIGTERM'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
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
  let observed = 'no response';
  while (Date.now() < deadline) {
    if (child && (child.startError || child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`Service exited before readiness; inspect ${directory}`);
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(7000) });
      await response.arrayBuffer();
      observed = `HTTP ${response.status}`;
      if (response.status === expectedStatus) return;
    } catch (error) { observed = `${error.name}: ${error.cause?.code ?? 'request failed'}`; }
    await delay(500);
  }
  throw new Error(`Readiness GET ${url}: expected ${expectedStatus}, last ${observed}; inspect ${directory}`);
}
const javaUrl = `http://127.0.0.1:${values.JAVA_PORT}`;
const webUrl = `http://${identityMode || workspaceMode ? 'localhost' : '127.0.0.1'}:${values.WEB_PORT}`;
function sandbox() {
  const child = start('sandbox', 'docker', [...composeArgs, 'up', '--no-deps', 'sandbox'], composeEnv);
  child.sandboxContainer = true;
  return child;
}
function java() {
  Object.assign(composeEnv, javaComposeEnv(values));
  const child = start('java', 'docker', [...composeArgs, 'up', '--no-deps', '--abort-on-container-exit', '--exit-code-from', 'java', 'java'], composeEnv);
  child.javaContainer = true;
  return child;
}
let fixtures;
let browserRun = 0;
async function browser(database = 'AVAILABLE', sandboxState = 'AVAILABLE', javaState = 'AVAILABLE', files) {
  const selection = files ?? [identityMode ? 'identity.spec.ts' : 'status.spec.ts'];
  const output = join(directory, `browser-${++browserRun}`);
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [join(root, 'frontend/node_modules/@playwright/test/cli.js'), 'test', ...selection,
      ...(browserTest ? ['--grep', browserTest] : smoke ? ['--grep', '@smoke'] : [])], {
      cwd: join(root, 'frontend'), stdio: 'inherit',
      env: { ...serviceEnv(values, 'web'), TRACER_WEB_URL: webUrl, TRACER_SCREENSHOT_DIR: output,
        PLAYWRIGHT_OUTPUT_DIR: join(output, 'results'), PLAYWRIGHT_REPORT_DIR: join(output, 'report'),
        EXPECT_DATABASE: database, EXPECT_SANDBOX: sandboxState, EXPECT_JAVA: javaState,
        IDENTITY_E2E: identityMode ? 'true' : 'false', WORKSPACE_E2E: workspaceMode ? 'true' : 'false',
        IDENTITY_TEST_ADMIN: values.BOOTSTRAP_ADMIN_USERNAME, IDENTITY_TEST_PASSWORD: values.BOOTSTRAP_ADMIN_PASSWORD,
        IDENTITY_TEST_NEW_PASSWORD: updatedIdentityPassword,
        BROWSER_FIXTURE_URL: fixtures?.url ?? '', BROWSER_FIXTURE_KEY: fixtures?.key ?? '' },
    });
    child.once('error', reject);
    child.once('exit', (code) => code === 0 ? resolve() : reject(new Error(`Browser failed (${code}); artifacts: ${output}`)));
  });
}
function sandboxId() {
  return run('docker', [...composeArgs, 'ps', '-q', 'sandbox'],
    { env: composeEnv, encoding: 'utf8', stdio: 'pipe' }).stdout.trim();
}
function inspectJava() {
  const id = run('docker', [...composeArgs, 'ps', '-q', 'java'],
    { env: composeEnv, encoding: 'utf8', stdio: 'pipe' }).stdout.trim();
  assert(id, 'Java must run in the same Docker Linux daemon, not the host JVM');
  const info = JSON.parse(run('docker', ['inspect', id], { encoding: 'utf8', stdio: 'pipe' }).stdout)[0];
  assert.equal(info.Config.Image, composeEnv.JAVA_IMAGE);
  assert.equal(info.Config.User, '10001:10001');
  assert.equal(info.HostConfig.ReadonlyRootfs, true);
  assert(info.HostConfig.CapDrop.includes('ALL'));
  assert(info.HostConfig.SecurityOpt.some((value) => value.startsWith('no-new-privileges')));
  assert(info.Mounts.every((mount) => mount.Type === 'tmpfs' && mount.Destination === '/tmp'), 'Java must not receive source, workspace or Docker socket mounts');
  assert.equal(info.HostConfig.PortBindings['8080/tcp'][0].HostIp, '127.0.0.1');
}
function inspectSandbox() {
  const id = sandboxId();
  assert(id, 'sandbox container missing');
  const info = JSON.parse(run('docker', ['inspect', id], { encoding: 'utf8', stdio: 'pipe' }).stdout)[0];
  assert.equal(info.Config.User, '10001:10001');
  assert.equal(info.HostConfig.ReadonlyRootfs, true);
  assert(info.HostConfig.CapDrop.includes('ALL'));
  assert(info.HostConfig.SecurityOpt.some((value) => value.startsWith('no-new-privileges')));
  assert.equal(info.Mounts.length, 2, 'trusted coordinator gets private data and Docker socket only');
  assert(info.Mounts.some((mount) => mount.Type === 'volume' && mount.Destination === '/var/lib/codecraft'));
  assert(info.Mounts.some((mount) => mount.Type === 'bind' && mount.Destination === '/docker.sock'));
  assert.equal(info.HostConfig.PortBindings['9090/tcp'][0].HostIp, '127.0.0.1');
  assert(!info.Config.Env.some((value) => /^(POSTGRES_|BOOTSTRAP_|IDENTITY_)/.test(value)));
  const second = spawnSync('docker', ['exec', id, '/sandbox', '--container'], { encoding: 'utf8', timeout: 10000 });
  assert.equal(second.status, 1, 'second storage owner must be rejected');
  assert(second.stderr.includes('acquire exclusive storage ownership'), 'must reject the storage lock, not merely the port');
  return id;
}
let failure;
try {
  run('docker', ['info'], { stdio: 'ignore' });
  if (workspaceMode) run(process.execPath, ['scripts/publish-problem.mjs', 'problems/cart-total/1.0.1',
    join(directory, 'publication-evidence'), values.PROBLEM_PUBLICATION_FILE]);
  composeEnv.SANDBOX_IMAGE = await buildSandboxImage(values.PROBLEM_PUBLICATION_FILE);
  composeEnv.SANDBOX_PORT = values.SANDBOX_PORT;
  composeEnv.JAVA_IMAGE = await buildJavaImage(values.PROBLEM_PUBLICATION_FILE);
  Object.assign(composeEnv, javaComposeEnv(values));
  // Fail fast if built artifacts or matching browser are missing; see README setup.
  compose(['up', '-d', '--wait', 'postgres']);
  if (identityMode && !smoke) {
    compose(['exec', '-T', 'postgres', 'psql', '-U', values.POSTGRES_USER, '-d', values.POSTGRES_DB,
      '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE identity_boundary']);
    await linuxJavaTests({ directory, project, values, database: 'identity_boundary', mode: 'identity' });
  }
  let goProcess = sandbox();
  let javaProcess = java();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  inspectJava();
  if (workspaceMode && !smoke) {
    compose(['exec', '-T', 'postgres', 'psql', '-U', values.POSTGRES_USER, '-d', values.POSTGRES_DB,
      '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE workspace_boundary']);
    if (!recoveryOnly) await linuxJavaTests({ directory, project, values, database: 'workspace_boundary', mode: 'workspace',
      sandbox: sandboxId(), test: workspaceTest ? `WorkspaceIT#${workspaceTest}` : undefined });
    if (!workspaceTest) {
      compose(['exec', '-T', 'postgres', 'psql', '-U', values.POSTGRES_USER, '-d', values.POSTGRES_DB,
        '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE workspace_recovery']);
      await linuxJavaTests({ directory, project, values, database: 'workspace_recovery', mode: 'recovery', test: 'WorkspaceCrashRecoveryIT' });
    }
  }
  if (workspaceMode) fixtures = await browserFixtures({ project, composeArgs, env: composeEnv,
    username: values.POSTGRES_USER, database: values.POSTGRES_DB });
  if (smoke) {
    const webProcess = start('web', process.execPath,
      [join(root, 'frontend/node_modules/vite/bin/vite.js'), 'preview', join(root, 'frontend'), '--config', join(root, 'frontend/vite.config.ts')],
      serviceEnv(values, 'web'));
    await waitFor(webUrl, 200, webProcess);
    await browser('AVAILABLE', 'AVAILABLE', 'AVAILABLE', [
      ...(identityMode ? ['identity.spec.ts'] : []), ...(workspaceMode ? ['workspace.spec.ts'] : []),
    ]);
  } else if (!boundaryOnly) {
  const initialSandbox = inspectSandbox();
  const marker = join(directory, 'volume-marker.txt');
  await writeFile(marker, project);
  run('docker', ['cp', marker, `${initialSandbox}:/var/lib/codecraft/volume-marker.txt`]);
  await linuxJavaTests({ directory, project, values, database: values.POSTGRES_DB, mode: 'tracer', url: 'http://java:8080' });
  const webProcess = start('web', process.execPath,
    [join(root, 'frontend/node_modules/vite/bin/vite.js'), 'preview', join(root, 'frontend'), '--config', join(root, 'frontend/vite.config.ts')],
    serviceEnv(values, 'web'));
  // Vite's root is explicitly supplied because this process starts in the repository root.
  await waitFor(webUrl, 200, webProcess);
  console.log('Browser: all dependencies available');
  await browser();
  if (workspaceMode) await browser('AVAILABLE', 'AVAILABLE', 'AVAILABLE', ['workspace.spec.ts']);
  if (identityMode) {
    const csrfResponse = await fetch(`${webUrl}/api/auth/csrf`);
    const csrfBody = await csrfResponse.json();
    const anonymousCookie = csrfResponse.headers.getSetCookie().map((value) => value.split(';')[0]).join('; ');
    const loginResponse = await fetch(`${webUrl}/api/auth/login`, { method: 'POST',
      headers: { Origin: webUrl, Cookie: anonymousCookie, 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfBody.data.token },
      body: JSON.stringify({ username: values.BOOTSTRAP_ADMIN_USERNAME, password: updatedIdentityPassword }) });
    if (loginResponse.status !== 200) throw new Error('Identity restart probe could not authenticate');
    const sessionCookie = loginResponse.headers.getSetCookie().find((value) => value.startsWith('codecraft_session='))?.split(';')[0];
    if (!sessionCookie) throw new Error('Identity restart probe did not receive a session');
    await stop(javaProcess);
    // Existing administrators must survive changed bootstrap configuration on a real restart.
    values.BOOTSTRAP_ADMIN_PASSWORD = randomBytes(32).toString('hex');
    javaProcess = java();
    await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
    const resumed = await fetch(`${webUrl}/api/auth/session`, { headers: { Cookie: sessionCookie } });
    if (resumed.status !== 200) throw new Error('Database session did not survive Java restart');
    console.log('Identity: database session survived Java restart and changed bootstrap configuration');
  } else {
  await stop(goProcess);
  await waitFor(`${javaUrl}/api/internal/status`, 503, javaProcess);
  console.log('Browser: Go stopped');
  await browser('AVAILABLE', 'UNAVAILABLE');
  compose(['rm', '-f', 'sandbox']);
  goProcess = sandbox();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  const recreatedSandbox = inspectSandbox();
  assert.notEqual(recreatedSandbox, initialSandbox);
  const recoveredMarker = join(directory, 'recovered-marker.txt');
  run('docker', ['cp', `${recreatedSandbox}:/var/lib/codecraft/volume-marker.txt`, recoveredMarker]);
  assert.equal(await readFile(recoveredMarker, 'utf8'), project);
  console.log('Sandbox: Linux ownership checks and persistent volume survived container recreation');
  compose(['stop', 'postgres']);
  await waitFor(`${javaUrl}/api/internal/status`, 503, javaProcess);
  console.log('Browser: database stopped');
  await browser('UNAVAILABLE', 'AVAILABLE');
  compose(['start', '--wait', 'postgres']);
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  await stop(javaProcess);
  javaProcess = java();
  await waitFor(`${javaUrl}/api/internal/status`, 200, javaProcess);
  console.log('Browser: restart and migration revalidation');
  await browser();
  await stop(javaProcess);
  console.log('Browser: Java stopped');
  await browser('UNAVAILABLE', 'UNAVAILABLE', 'UNAVAILABLE');
  compose(['stop', 'postgres']);
  console.log('Startup: database/migration unavailable must fail closed');
  const failedStartup = java();
  const startupDeadline = Date.now() + 30000;
  while (failedStartup.exitCode === null && !failedStartup.startError && Date.now() < startupDeadline) await delay(250);
  if (failedStartup.startError || failedStartup.exitCode === null || failedStartup.exitCode === 0) {
    throw new Error('Expected Java to exit nonzero when its migration database is unavailable');
  }
  }
  }
} catch (error) {
  failure = error;
} finally {
  const failures = failure ? [failure] : [];
  if (fixtures) { try { await fixtures.close(); } catch (error) { failures.push(error); } }
  for (const child of children.reverse()) {
    try { await stop(child); } catch (error) { failures.push(error); }
  }
  // A killed test JVM must not leave its exact managed execution containers behind.
  try {
    const ids = run('docker', ['ps', '-aq', '--filter', `label=codecraft.execution.volume=${project}_sandbox-data`],
      { encoding: 'utf8', stdio: 'pipe' }).stdout.trim();
    if (ids) run('docker', ['rm', '-f', ...ids.split(/\s+/)]);
  } catch (error) { failures.push(error); }
  try { compose(['down', '--volumes', '--remove-orphans']); } catch (error) { failures.push(error); }
  try { await rm(envFile, { force: true }); } catch (error) { failures.push(error); }
  failure = failures.length > 1 ? new AggregateError(failures, `Test/cleanup failed: ${project}; logs: ${directory}`) : failures[0];
}
if (failure) throw failure;
console.log(`Real ${smoke ? 'smoke' : identityMode ? 'identity' : 'tracer'} suite passed. Logs: ${directory}`);
