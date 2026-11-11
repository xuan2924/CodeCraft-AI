import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run } from './process.mjs';

export const imageRepositories = ['codecraft/java', 'codecraft/sandbox', 'codecraft/cart-total'];
export const currentImageDirectory = join(root, '.runtime', 'docker-current');
export function docker(args) {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 30000, maxBuffer: 8 * 1024 * 1024 }).trim();
}

export function imageTag(repository, inputs) {
  if (!imageRepositories.includes(repository)) throw new Error('Unknown project image repository');
  const hash = createHash('sha256').update('linux/amd64\0');
  for (const input of inputs) {
    const bytes = Buffer.from(input);
    hash.update(String(bytes.length)).update(':').update(bytes);
  }
  return `${repository}:local-${hash.digest('hex')}`;
}

// Explicit current references replace arbitrary "keep N old versions" retention.
export async function rememberImage(repository, id) {
  if (!imageRepositories.includes(repository) || !/^sha256:[0-9a-f]{64}$/.test(id)) throw new Error('Invalid current image');
  await mkdir(currentImageDirectory, { recursive: true });
  const file = join(currentImageDirectory, repository.split('/')[1] + '.json');
  const temporary = `${file}.${randomUUID()}.tmp`;
  await writeFile(temporary, JSON.stringify({ repository, id }));
  await rename(temporary, file);
}

export async function ensureImage(repository, inputs, directory) {
  const tag = imageTag(repository, inputs);
  // An unavailable daemon is an error, not a cache miss.
  if (!docker(['image', 'ls', '--quiet', '--no-trunc', tag])) {
    run('docker', ['build', '--platform', 'linux/amd64', '-t', tag, directory], { timeout: 240000 });
  } else console.log(`Reusing ${tag}`);
  const id = docker(['image', 'inspect', '--format', '{{.Id}}', tag]);
  await rememberImage(repository, id);
  return tag;
}

export async function buildProblemImage(directory, packageSha256) {
  return ensureImage('codecraft/cart-total', [packageSha256, await readFile(join(directory, 'Dockerfile'))], directory);
}
