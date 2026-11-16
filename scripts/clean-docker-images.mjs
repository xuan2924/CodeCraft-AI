import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { docker, currentImageDirectory } from './docker-images.mjs';
import { cleanupPlan, publicationImageIds, validateCurrentReference, containerPublicationPath } from './docker-cleanup-policy.mjs';
import { loadConfig } from './local-config.mjs';

const args = process.argv.slice(2);
let apply = false;
const keep = [];
const publications = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--apply') apply = true;
  else if (['--keep', '--publication'].includes(args[i]) && args[i + 1] && !args[i + 1].startsWith('--')) {
    (args[i] === '--keep' ? keep : publications).push(args[++i]);
  } else throw new Error('Usage: node scripts/clean-docker-images.mjs [--apply] [--keep IMAGE] [--publication FILE]');
}
const ids = (command) => [...new Set(docker(command).split(/\s+/).filter(Boolean))];
const inspect = (kind, values) => values.length ? JSON.parse(docker([kind, 'inspect', ...values])) : [];
const protectedIds = new Set(inspect('image', keep).map((image) => image.Id));
let entries;
try { entries = await readdir(currentImageDirectory); }
catch (error) { if (error.code !== 'ENOENT') throw error; entries = []; }
for (const entry of entries.filter((name) => name.endsWith('.json'))) {
  protectedIds.add(validateCurrentReference(JSON.parse(await readFile(join(currentImageDirectory, entry), 'utf8'))));
}
// Only the active development publication, not every historical test receipt.
try {
  const config = await loadConfig();
  if (config.PROBLEM_PUBLICATION_FILE) publications.push(config.PROBLEM_PUBLICATION_FILE);
} catch (error) { if (error.code !== 'ENOENT') throw error; }
for (const file of publications) {
  for (const id of publicationImageIds(JSON.parse(await readFile(resolve(file), 'utf8')))) protectedIds.add(id);
}
const containers = inspect('container', ids(['ps', '-aq', '--no-trunc']));
const scratch = await mkdtemp(join(tmpdir(), 'codecraft-image-cleanup-'));
try {
  for (const container of containers) {
    protectedIds.add(container.Image);
    // Include stopped containers, and fail closed if their publication cannot be read.
    const image = inspect('image', [container.Image])[0];
    const publicationPath = containerPublicationPath(container, image);
    if (!publicationPath) continue;
    const file = join(scratch, `${container.Id}.json`);
    docker(['cp', `${container.Id}:${publicationPath}`, file]);
    for (const id of publicationImageIds(JSON.parse(await readFile(file, 'utf8')))) protectedIds.add(id);
  }
} finally { await rm(scratch, { recursive: true, force: true }); }
const images = inspect('image', ids(['image', 'ls', '-aq', '--no-trunc']));
// No guess based on creation time: an uninitialised repo must be pinned explicitly.
for (const repository of ['codecraft/java', 'codecraft/sandbox', 'codecraft/cart-total']) {
  const own = images.filter((image) => image.RepoTags?.some((tag) => tag.startsWith(repository + ':')));
  if (own.length && !own.some((image) => protectedIds.has(image.Id))) {
    throw new Error(`No current reference for ${repository}; build it or supply --keep IMAGE before cleanup`);
  }
}
const plan = cleanupPlan(images, protectedIds);
for (const item of plan) console.log(`REMOVE ${item.id.slice(0, 19)} ${item.tags.join(', ')}`);
console.log(`${apply ? 'Apply' : 'Preview'}: ${plan.length} images, ${plan.reduce((count, item) => count + item.tags.length, 0)} tags. Containers/volumes/build cache are untouched.`);
if (apply) {
  // Run with builds/tests stopped. Recheck all container references before each
  // deletion; never force. Docker remains the final guard against image-use races.
  for (const item of plan) {
    const live = inspect('container', ids(['ps', '-aq', '--no-trunc']));
    if (live.some((container) => container.Image === item.id)) {
      console.log(`SKIP newly referenced ${item.id}`); continue;
    }
    const latest = inspect('image', [item.id])[0];
    if ((latest.RepoTags ?? []).some((tag) => !images.find((image) => image.Id === item.id).RepoTags.includes(tag))) {
      throw new Error('Image tags changed during cleanup; rerun preview');
    }
    for (const tag of item.tags) {
      if (live.some((container) => container.Config.Image === tag)) continue;
      const actual = inspect('image', [tag])[0];
      if (actual.Id !== item.id) throw new Error('Image tag changed during cleanup; refusing deletion');
      docker(['image', 'rm', '--no-prune', tag]);
    }
  }
}
