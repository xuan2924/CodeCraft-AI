import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run } from './process.mjs';
import { buildSandboxImage } from './build-sandbox.mjs';

await mkdir(join(root, '.runtime'), { recursive: true });
const directory = await mkdtemp(join(root, '.runtime', 'workspace-tests-'));
try {
  const binary = join(directory, 'workspace.test');
  const go = { cwd: join(root, 'backend/sandbox'), env: { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' } };
  run('go', ['test', '-c', '-o', binary, './internal/workspace'], go);
  const image = await buildSandboxImage();
  run('docker', ['run', '--rm', '--platform', 'linux/amd64', '--network', 'none', '--read-only',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '10001:10001',
    '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777',
    '--mount', `type=bind,source=${binary},target=/workspace.test,readonly`,
    '--entrypoint', '/workspace.test', image, '-test.v', '-test.timeout=60s', ...process.argv.slice(2)]);
} finally { await rm(directory, { recursive: true, force: true }); }
