import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root, run } from './process.mjs';
import { verifyPublicationInputs } from './publication-inputs.mjs';

// Fresh context: never send the repository, .env or user workspaces to Docker.
export async function buildSandboxImage(publicationFile = null) {
  await mkdir(join(root, '.runtime'), { recursive: true });
  const directory = await mkdtemp(join(root, '.runtime', 'sandbox-image-'));
  try {
    await mkdir(join(directory, 'state'));
    await writeFile(join(directory, 'state', '.keep'), '');
    const publication = publicationFile ? JSON.parse(await readFile(publicationFile, 'utf8')) : null;
    if (publication) verifyPublicationInputs(publication);
    // Whitelist only initial inputs; neither credentials nor reference solutions enter the image.
    const inputs = publication ? [{ identity: publication.identity, initialFiles: publication.initialFiles }] : [];
    await writeFile(join(directory, 'publications.json'), JSON.stringify(inputs));
    await copyFile(join(root, 'backend/sandbox/Dockerfile'), join(directory, 'Dockerfile'));
    run('go', ['build', '-trimpath', '-o', join(directory, 'sandbox'), './cmd/sandbox'], {
      cwd: join(root, 'backend/sandbox'),
      env: { ...process.env, GOOS: 'linux', GOARCH: 'amd64', CGO_ENABLED: '0' },
    });
    const hash = createHash('sha256').update(await readFile(join(directory, 'sandbox')))
      .update(await readFile(join(directory, 'Dockerfile')))
      .update(await readFile(join(directory, 'publications.json'))).digest('hex');
    const image = `codecraft/sandbox:local-${hash.slice(0, 24)}`;
    run('docker', ['build', '--platform', 'linux/amd64', '-t', image, directory]);
    return image;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(await buildSandboxImage());
}
