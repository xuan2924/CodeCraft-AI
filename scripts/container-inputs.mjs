import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { root } from './process.mjs';
import { verifyPublicationInputs } from './publication-inputs.mjs';

export function containerInputs(publication) {
  if (publication) verifyPublicationInputs(publication);
  const { globalConcurrency: _globalConcurrency, platform: _platform, ...limits } = publication?.manifest.runtime ?? {};
  const execution = publication?.manifest.execution
    ? { limits, suite: publication.manifest.execution.suite, tests: publication.manifest.execution.tests } : undefined;
  return {
    'publication.json': JSON.stringify(publication ?? {}),
    'publications.json': JSON.stringify(publication ? [{ identity: publication.identity, initialFiles: publication.initialFiles, execution }] : []),
  };
}

// Content-addressed, read-only runtime snapshots outlive stopped containers.
// Only validated publication data enters this directory, never .env or repository files.
export async function prepareContainerInputs(publicationFile) {
  const files = containerInputs(publicationFile ? JSON.parse(await readFile(publicationFile, 'utf8')) : null);
  const digest = createHash('sha256').update(JSON.stringify(files)).digest('hex');
  const directory = join(root, '.runtime', 'container-inputs', digest);
  await mkdir(directory, { recursive: true });
  for (const [name, content] of Object.entries(files)) {
    const path = join(directory, name);
    try { await writeFile(path, content, { flag: 'wx', mode: 0o444 }); }
    catch (error) {
      if (error.code !== 'EEXIST') throw error;
      if (await readFile(path, 'utf8') !== content) throw new Error('Runtime input snapshot differs from its content hash');
    }
  }
  return { CONTAINER_INPUTS_DIRECTORY: directory.replaceAll('\\', '/') };
}
