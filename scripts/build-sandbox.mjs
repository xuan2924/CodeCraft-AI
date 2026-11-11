import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root, run } from './process.mjs';
import { ensureImage } from './docker-images.mjs';

// Fresh context: never send the repository, .env or user workspaces to Docker.
export async function buildSandboxImage() {
  await mkdir(join(root, '.runtime'), { recursive: true });
  const directory = await mkdtemp(join(root, '.runtime', 'sandbox-image-'));
  try {
    await mkdir(join(directory, 'state'));
    await writeFile(join(directory, 'state', '.keep'), '');
    await writeFile(join(directory, 'publications.json'), '[]');
    await copyFile(join(root, 'backend/sandbox/Dockerfile'), join(directory, 'Dockerfile'));
    run('go', ['build', '-trimpath', '-buildvcs=false', '-o', join(directory, 'sandbox'), './cmd/sandbox'], {
      cwd: join(root, 'backend/sandbox'),
      env: { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' },
    });
    const inputs = await Promise.all(['sandbox', 'Dockerfile', 'publications.json'].map((file) => readFile(join(directory, file))));
    return await ensureImage('codecraft/sandbox', inputs, directory);
  } finally { await rm(directory, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(await buildSandboxImage());
}
