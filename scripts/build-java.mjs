import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run } from './process.mjs';

// Build context contains only the packaged application and its publication, never .env/source.
export async function buildJavaImage(publicationFile) {
  await mkdir(join(root, '.runtime'), { recursive: true });
  const directory = await mkdtemp(join(root, '.runtime', 'java-image-'));
  try {
    await copyFile(join(root, 'backend/server/target/server-0.1.0-SNAPSHOT.jar'), join(directory, 'server.jar'));
    await copyFile(join(root, 'backend/server/Dockerfile'), join(directory, 'Dockerfile'));
    await writeFile(join(directory, 'publication.json'), publicationFile ? await readFile(publicationFile) : '{}');
    const hash = createHash('sha256');
    for (const file of ['Dockerfile', 'server.jar', 'publication.json']) hash.update(await readFile(join(directory, file)));
    const image = `codecraft/java:local-${hash.digest('hex').slice(0, 24)}`;
    run('docker', ['build', '--platform', 'linux/amd64', '-t', image, directory]);
    return image;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

// Explicit whitelist: Compose never inherits arbitrary Spring/JVM overrides from the host.
export function javaComposeEnv(values) {
  const env = {};
  for (const key of ['JAVA_PORT', 'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD',
    'IDENTITY_FINGERPRINT_KEY', 'SANDBOX_RPC_KEY']) env[key] = values[key] ?? '';
  env.PUBLIC_ORIGIN = values.PUBLIC_ORIGIN ?? `http://localhost:${values.WEB_PORT}`;
  env.JAVA_PUBLICATION_FILE = values.PROBLEM_PUBLICATION_FILE ? '/app/publication.json' : '';
  return env;
}
