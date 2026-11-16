import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run } from './process.mjs';
import { buildSandboxImage } from './build-sandbox.mjs';
import { validatePackage } from './problem-contract.mjs';
import { buildProblemImage } from './docker-images.mjs';

// Trusted SDK test process gets Docker management access. User execution
// containers are created by the adapter and NEVER receive this socket.
await mkdir(join(root, '.runtime'), { recursive: true });
const directory = await mkdtemp(join(root, '.runtime', 'execution-tests-'));
const volume = `cc-execution-test-${randomUUID()}`;
const owner = `cc-execution-fixture-${randomUUID()}`;
const fixture = join(root, 'problems/cart-total/1.1.0');
let volumeCreated = false;
let failure;
try {
  const { packageSha256 } = await validatePackage(fixture);
  const tag = await buildProblemImage(fixture, packageSha256);
  const image = run('docker', ['image', 'inspect', '--format', '{{.Id}}', tag], { encoding: 'utf8', stdio: 'pipe' }).stdout.trim();
  const binary = join(directory, 'execution.test');
  run('go', ['test', '-c', '-o', binary, './internal/execution'], {
    cwd: join(root, 'backend/sandbox'), env: { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' },
  });
  const runnerImage = await buildSandboxImage();
  run('docker', ['volume', 'create', volume]);
  volumeCreated = true;
  // Allow the trusted fixture coordinator to use the same uid as production Go
  // and candidate containers; stable-copy files intentionally remain mode 0600.
  run('docker', ['run', '--rm', '--network', 'none', '--read-only', '--user', '0:0',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--mount', `type=volume,source=${volume},target=/state`,
    '--entrypoint', '/bin/chmod', image, '0777', '/state']);
  run('docker', ['run', '--rm', '--name', owner, '--platform', 'linux/amd64', '--network', 'none', '--read-only',
    '--user', '10001:0', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777',
    '--mount', `type=volume,source=${volume},target=/state`,
    '--mount', 'type=bind,source=/var/run/docker.sock,target=/docker.sock',
    '--mount', `type=bind,source=${binary},target=/execution.test,readonly`,
    '--mount', `type=bind,source=${fixture},target=/fixture,readonly`,
    '--env', 'DOCKER_HOST=unix:///docker.sock', '--env', 'CODECRAFT_EXECUTION_TEST=1', '--env', `CODECRAFT_EXECUTION_VOLUME=${volume}`,
    '--env', `CODECRAFT_EXECUTION_IMAGE=${image}`,
    '--entrypoint', '/execution.test', runnerImage, '-test.v', '-test.timeout=180s', ...process.argv.slice(2)]);
} catch (error) {
  failure = error;
} finally {
  const cleanupErrors = [];
  // Labels bind cleanup to this test volume, never a development execution.
  try {
    const ids = run('docker', ['ps', '-aq', '--filter', `label=codecraft.execution.volume=${volume}`], { encoding: 'utf8', stdio: 'pipe' }).stdout.trim();
    if (ids) run('docker', ['rm', '-f', ...ids.split(/\s+/)]);
  } catch (error) { cleanupErrors.push(error); }
  try { if (volumeCreated) run('docker', ['volume', 'rm', volume]); }
  catch (error) { cleanupErrors.push(error); }
  try { await rm(directory, { recursive: true, force: true }); }
  catch (error) { cleanupErrors.push(error); }
  if (cleanupErrors.length) {
    failure = new AggregateError(failure ? [failure, ...cleanupErrors] : cleanupErrors,
      `Execution test cleanup failed for ${volume}`);
  }
}
if (failure) throw failure;
