import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { imageTag } from './docker-images.mjs';
import { cleanupPlan, publicationImageIds, validateCurrentReference } from './docker-cleanup-policy.mjs';
import { containerInputs } from './container-inputs.mjs';
import { readPublicationInputs, publicationInputsSha256 } from './publication-inputs.mjs';
import { validatePackage } from './problem-contract.mjs';

const id = (character) => `sha256:${character.repeat(64)}`;
const tag = (repo, character) => `${repo}:local-${character.repeat(64)}`;
const image = (repo, character) => ({ Id: id(character), RepoTags: [tag(repo, character)], RepoDigests: [] });

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

test('canonical problem image replaces legacy aliases without deleting its image', () => {
  const current = image('codecraft/cart-total', 'a');
  const usedAlias = 'codecraft/cart-total:execution-test-0123456789ab';
  const obsoleteAlias = 'codecraft/cart-total:validation-0123456789ab';
  current.RepoTags.push(usedAlias, obsoleteAlias);
  assert.deepEqual(cleanupPlan([current], new Set([current.Id]), new Set([usedAlias])), [
    { id: current.Id, tags: [obsoleteAlias], removeImage: false },
  ]);
  assert.equal(cleanupPlan([current], new Set())[0].removeImage, true);
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
  const directory = fileURLToPath(new URL('../problems/cart-total/1.0.1/', import.meta.url));
  const inputs = await readPublicationInputs(directory);
  const { manifest } = await validatePackage(directory);
  const publication = { ...inputs, manifest, identity: { execution_image_digest: id('a') },
    receipt: { publicationInputsSha256: publicationInputsSha256(inputs), validatedAt: 'first' } };
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
