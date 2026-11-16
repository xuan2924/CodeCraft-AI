import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import * as fs from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { root } from './process.mjs';
import { imageTag } from './docker-images.mjs';
import { cleanupPlan, publicationImageIds, validateCurrentReference, containerPublicationPath } from './docker-cleanup-policy.mjs';
import { containerInputs, persistContainerInputs } from './container-inputs.mjs';
import { readPublicationInputs, publicationInputsSha256 } from './publication-inputs.mjs';
import { validatePackage } from './problem-contract.mjs';

const id = (character) => `sha256:${character.repeat(64)}`;
const tag = (repo, character) => `${repo}:local-${character.repeat(64)}`;
const image = (repo, character) => ({ Id: id(character), RepoTags: [tag(repo, character)], RepoDigests: [] });

test('failed snapshot write stays unpublished and a retry succeeds', async (t) => {
  const parent = await fs.mkdtemp(join(tmpdir(), 'image-input-test-'));
  t.after(() => fs.rm(parent, { recursive: true, force: true }));
  const files = containerInputs(null);
  const failure = new Error('injected disk failure');
  await assert.rejects(persistContainerInputs(files, parent, { ...fs, writeFile: async (path) => {
    await fs.writeFile(path, 'partial');
    throw failure;
  } }), /injected disk failure/);
  assert.deepEqual(await fs.readdir(parent), [], 'failed publication must not expose incomplete snapshots');
  const result = await persistContainerInputs(files, parent);
  assert.equal(await fs.readFile(join(result.CONTAINER_INPUTS_DIRECTORY, 'publication.json'), 'utf8'), '{}');
});

test('concurrent snapshot preparation returns the same complete directory', async (t) => {
  const parent = await fs.mkdtemp(join(tmpdir(), 'image-input-test-'));
  t.after(() => fs.rm(parent, { recursive: true, force: true }));
  const files = containerInputs(null);
  const prepared = await Promise.all(Array.from({ length: 4 }, () => persistContainerInputs(files, parent)));
  assert.equal(new Set(prepared.map((value) => value.CONTAINER_INPUTS_DIRECTORY)).size, 1);
  assert.equal((await fs.readdir(parent)).length, 1);
  for (const [name, content] of Object.entries(files)) {
    assert.equal(await fs.readFile(join(prepared[0].CONTAINER_INPUTS_DIRECTORY, name), 'utf8'), content);
  }
});

test('container publication discovery survives removal of current image tags', () => {
  assert.equal(containerPublicationPath({ Config: { Image: 'codecraft/java:local-old' } }, { RepoTags: [] }), '/app/publication.json');
  assert.equal(containerPublicationPath({ Config: { Image: id('a') }, Mounts: [{ Destination: '/publications.json' }] }, { RepoTags: ['other:alias'] }), '/publications.json');
  assert.equal(containerPublicationPath({ Config: { Image: id('a'), Labels: { 'com.docker.compose.service': 'sandbox', 'com.docker.compose.project.working_dir': root } } }, { RepoTags: [] }), '/publications.json');
  assert.equal(containerPublicationPath({ Config: { Image: 'postgres:17' } }, { RepoTags: ['postgres:17'] }), null);
  assert.equal(containerPublicationPath({ Config: { Image: id('a'), Labels: { 'com.docker.compose.service': 'sandbox', 'com.docker.compose.project.working_dir': '/another/project' } } }, { RepoTags: [] }), null);
});

test('image keys are deterministic, ordered and sensitive to build artifacts', () => {
  const first = imageTag('codecraft/java', ['Dockerfile', 'jar']);
  assert.equal(imageTag('codecraft/java', [Buffer.from('Dockerfile'), Buffer.from('jar')]), first);
  assert.notEqual(imageTag('codecraft/java', ['Dockerfile', 'changed jar']), first);
  assert.notEqual(imageTag('codecraft/java', ['ab', 'c']), imageTag('codecraft/java', ['a', 'bc']));
  assert.throws(() => imageTag('unrelated/project', []));
});

test('cleanup protects current/container/publication IDs and never foreign images', () => {
  const images = [image('codecraft/java', 'a'), image('codecraft/java', 'b'), image('codecraft/sandbox', 'c'), image('codecraft/cart-total', 'd'),
    { Id: id('e'), RepoTags: ['other/project:latest'] }, { Id: id('f'), RepoTags: [] }];
  assert.deepEqual(cleanupPlan(images, new Set([id('a'), id('c'), id('d')])).map((item) => item.id), [id('b')]);
  images[1].RepoTags.push('other/project:borrowed');
  assert.deepEqual(cleanupPlan(images, new Set([id('a'), id('c'), id('d')])), []);
});

test('cleanup ignores retired tag formats and protects registry references', () => {
  const current = image('codecraft/cart-total', 'a');
  assert.deepEqual(cleanupPlan([current], new Set([current.Id])), []);
  assert.deepEqual(cleanupPlan([current], new Set()), [{ id: current.Id, tags: current.RepoTags }]);
  current.RepoTags.push('codecraft/cart-total:validation-0123456789ab');
  assert.deepEqual(cleanupPlan([current], new Set()), [], 'unknown aliases are never removed implicitly');
  current.RepoTags = [tag('codecraft/cart-total', 'a')];
  current.RepoDigests = ['registry.example/project@' + id('a')];
  assert.deepEqual(cleanupPlan([current], new Set()), []);
});

test('publication references fail closed and do not include random receipt fields', () => {
  assert.deepEqual(publicationImageIds([{ identity: { execution_image_digest: id('a') } }, {}]), [id('a')]);
  assert.throws(() => publicationImageIds({ receipt: { imageId: id('a') } }));
  assert.throws(() => publicationImageIds(null));
  assert.equal(validateCurrentReference({ repository: 'codecraft/cart-total', id: id('b') }), id('b'));
  assert.throws(() => validateCurrentReference({ repository: 'other/project', id: id('b') }));
});

test('runtime publications preserve verification, separate volatile receipts and exclude answers from Go', async () => {
  const directory = fileURLToPath(new URL('../problems/cart-total/1.1.0/', import.meta.url));
  const inputs = await readPublicationInputs(directory);
  const { manifest } = await validatePackage(directory);
  const publication = { ...inputs, manifest, identity: { execution_image_digest: id('a') },
    receipt: { publicationInputsSha256: publicationInputsSha256(inputs), validatedAt: 'first',
      challenge: { publicationInputsSha256: publicationInputsSha256(inputs.challenge), packageSha256: 'b'.repeat(64), imageId: id('b'), platform: 'linux/amd64' } } };
  const first = containerInputs(publication);
  publication.receipt.validatedAt = 'second';
  const second = containerInputs(publication);
  assert.notEqual(first['publication.json'], second['publication.json']);
  assert.equal(first['publications.json'], second['publications.json']);
  const go = JSON.parse(second['publications.json'])[0];
  assert.deepEqual(go.initialFiles, inputs.initialFiles);
  assert.equal(go.acceptanceFiles, undefined);
  assert.equal(go.receipt, undefined);
  publication.initialFiles['pom.xml'] += 'tampered';
  assert.throws(() => containerInputs(publication), /Published inputs/);
  assert.deepEqual(containerInputs(null), { 'publication.json': '{}', 'publications.json': '[]' });
});
