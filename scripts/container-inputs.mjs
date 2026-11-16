import { createHash } from 'node:crypto';
import * as fs from 'node:fs/promises';
import { join } from 'node:path';
import { root } from './process.mjs';
import { verifyPublicationInputs } from './publication-inputs.mjs';

export function containerInputs(publication) {
  if (publication) verifyPublicationInputs(publication);
  const { globalConcurrency: _globalConcurrency, platform: _platform, ...limits } = publication?.manifest.runtime ?? {};
  const execution = publication?.manifest.execution
    ? { limits, suite: publication.manifest.execution.suite, tests: publication.manifest.execution.tests } : undefined;
  let challenge;
  if (publication?.challenge) {
    const child = publication.challenge;
    const receipt = publication.receipt.challenge;
    verifyPublicationInputs({ ...child, receipt });
    const { globalConcurrency: _childConcurrency, platform: _childPlatform, ...childLimits } = child.manifest.runtime;
    challenge = { identity: { problem_id: child.manifest.id, version: child.manifest.version,
      package_sha256: receipt.packageSha256, execution_image_digest: receipt.imageId, platform: receipt.platform },
    execution: { limits: childLimits, suite: child.manifest.execution.suite, tests: child.manifest.execution.tests } };
  }
  return {
    'publication.json': JSON.stringify(publication ?? {}),
    'publications.json': JSON.stringify(publication ? [{ identity: publication.identity, initialFiles: publication.initialFiles, execution, ...(challenge ? { challenge } : {}) }] : []),
  };
}

// Content-addressed, read-only runtime snapshots outlive stopped containers.
// Only validated publication data enters this directory, never .env or repository files.
export async function prepareContainerInputs(publicationFile) {
  const files = containerInputs(publicationFile ? JSON.parse(await fs.readFile(publicationFile, 'utf8')) : null);
  return persistContainerInputs(files);
}

export async function persistContainerInputs(files, parent = join(root, '.runtime', 'container-inputs'), io = fs) {
  const digest = createHash('sha256').update(JSON.stringify(files)).digest('hex');
  const directory = join(parent, digest);
  await io.mkdir(parent, { recursive: true });
  const staging = await io.mkdtemp(join(parent, '.stage-'));
  let failure;
  try {
    for (const [name, content] of Object.entries(files)) {
      await io.writeFile(join(staging, name), content, { flag: 'wx', mode: 0o444 });
    }
    try { await io.rename(staging, directory); }
    catch (error) {
      // Another caller may have published this exact snapshot first. Windows
      // and POSIX report different rename errors for an existing directory.
      for (const [name, content] of Object.entries(files)) {
        let existing;
        try { existing = await io.readFile(join(directory, name), 'utf8'); }
        catch { throw error; }
        if (existing !== content) throw new Error('Runtime input snapshot differs from its content hash', { cause: error });
      }
    }
  } catch (error) { failure = error; }
  finally {
    try { await io.rm(staging, { recursive: true, force: true }); }
    catch (error) { failure = failure ? new AggregateError([failure, error], 'Snapshot publication and cleanup failed') : error; }
  }
  if (failure) throw failure;
  return { CONTAINER_INPUTS_DIRECTORY: directory.replaceAll('\\', '/') };
}
