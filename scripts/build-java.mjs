import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { root } from './process.mjs';
import { ensureImage } from './docker-images.mjs';

// Runtime publications are mounted separately; only stable artifacts enter the image.
export async function buildJavaImage() {
  await mkdir(join(root, '.runtime'), { recursive: true });
  const directory = await mkdtemp(join(root, '.runtime', 'java-image-'));
  try {
    await copyFile(join(root, 'backend/server/target/server-0.1.0-SNAPSHOT.jar'), join(directory, 'server.jar'));
    await copyFile(join(root, 'backend/server/Dockerfile'), join(directory, 'Dockerfile'));
    await writeFile(join(directory, 'publication.json'), '{}');
    const inputs = await Promise.all(['Dockerfile', 'server.jar', 'publication.json'].map((file) => readFile(join(directory, file))));
    return await ensureImage('codecraft/java', inputs, directory);
  } finally { await rm(directory, { recursive: true, force: true }); }
}

// Explicit whitelist: Compose never inherits arbitrary Spring/JVM overrides from the host.
export function javaComposeEnv(values) {
  const env = {};
  for (const key of ['JAVA_PORT', 'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD',
    'IDENTITY_FINGERPRINT_KEY', 'SANDBOX_RPC_KEY', 'MODEL_CREDENTIAL_ACTIVE_KEY_ID', 'MODEL_CREDENTIAL_KEYS']) env[key] = values[key] ?? '';
  env.MODEL_CREDENTIAL_ROTATION_ENABLED = values.MODEL_CREDENTIAL_ROTATION_ENABLED ?? 'false';
  env.COPILOT_WEB_API_KEY = values.COPILOT_WEB_API_KEY ?? '';
  env.COPILOT_WEB_BASE_URL = values.COPILOT_WEB_BASE_URL ?? 'https://api.tavily.com/';
  env.COPILOT_WEB_TIMEOUT = values.COPILOT_WEB_TIMEOUT ?? '20s';
  env.PUBLIC_ORIGIN = values.PUBLIC_ORIGIN ?? `http://localhost:${values.WEB_PORT}`;
  env.JAVA_PUBLICATION_FILE = values.PROBLEM_PUBLICATION_FILE ? '/app/publication.json' : '';
  return env;
}
