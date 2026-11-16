import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validatePackage } from './problem-contract.mjs';
import { publicationFromSnapshot } from './publication-contract.mjs';
import { containerInputs } from './container-inputs.mjs';
import { publicationInputsSha256, readPublicationInputs, verifyPublicationInputs } from './publication-inputs.mjs';

const directory = fileURLToPath(new URL('../problems/cart-total/1.0.0/', import.meta.url));

test('challenge requires independent validation and its hidden inputs remain bound to the parent receipt', async (t) => {
    const root = await mkdtemp(join(tmpdir(), 'challenge-publication-'));
    t.after(() => rm(root, { recursive: true, force: true }));
    await cp(directory, root, { recursive: true });
    await cp(directory, join(root, 'challenge'), { recursive: true });
    const manifest = JSON.parse(await readFile(join(root, 'manifest.json'), 'utf8'));
    manifest.execution = { suite: 'interview.CartTotalTest', tests: ['emptyCartIsFree', 'sumsPriceTimesQuantityInCents', 'allowsZeroPriceAndZeroQuantity',
        'rejectsNegativePrice', 'rejectsNegativeQuantity', 'rejectsNullCartAndItem', 'detectsMultiplicationOverflow', 'detectsAdditionOverflow'] };
    await writeFile(join(root, 'challenge', 'manifest.json'), JSON.stringify(manifest));
    manifest.challenge = 'challenge';
    await writeFile(join(root, 'manifest.json'), JSON.stringify(manifest));
    const host = { daemonId: 'fixture-daemon', imageId: `sha256:${'a'.repeat(64)}`, platform: 'linux/amd64',
        challenge: { imageId: `sha256:${'b'.repeat(64)}`, platform: 'linux/amd64' } };
    async function receipt(path, imageId) {
        const validated = await validatePackage(path);
        return { publicationInputsSha256: publicationInputsSha256(await readPublicationInputs(path)),
            verdict: 'local-contract-validation-passed', packageSha256: validated.packageSha256, imageId,
            platform: host.platform, limits: manifest.runtime, environment: { daemonId: host.daemonId },
            results: [{ name: 'initial', testSummary: manifest.expectedInitial }, { name: 'reference', testSummary: manifest.expectedReference }] };
    }
    const base = await receipt(root, host.imageId);
    await assert.rejects(publicationFromSnapshot(root, base, host), /Challenge requires/);
    base.challenge = await receipt(join(root, 'challenge'), `sha256:${'b'.repeat(64)}`);
    const publication = await publicationFromSnapshot(root, base, host);
    assert.equal(publication.receipt.challenge.imageId, `sha256:${'b'.repeat(64)}`);
    const runtime = JSON.parse(containerInputs(publication)['publications.json'])[0];
    assert.equal(runtime.identity.execution_image_digest, host.imageId);
    assert.equal(runtime.challenge.identity.execution_image_digest, host.challenge.imageId);
    assert.equal(runtime.challenge.initialFiles, undefined);
    assert.equal(runtime.challenge.acceptanceFiles, undefined);
    assert.equal(runtime.challenge.requirements, undefined);
    assert.deepEqual(runtime.initialFiles, publication.initialFiles);
    const altered = structuredClone(publication);
    altered.challenge.requirements = 'different challenge';
    assert.throws(() => verifyPublicationInputs(altered), /Published inputs/);
    const invalid = structuredClone(base);
    invalid.challenge.results[1].testSummary = { tests: 0, failures: 0, errors: 0, skipped: 0 };
    await assert.rejects(publicationFromSnapshot(root, invalid, host));
});

test('publication binds verification to package, immutable image and local daemon; excludes reference', async () => {
    const { manifest, packageSha256 } = await validatePackage(directory);
    const imageId = `sha256:${'a'.repeat(64)}`;
    const host = { daemonId: 'fixture-daemon', imageId, platform: 'linux/amd64' };
    const receipt = { publicationInputsSha256: publicationInputsSha256(await readPublicationInputs(directory)), verdict: 'local-contract-validation-passed', packageSha256, imageId,
        platform: host.platform, limits: manifest.runtime, environment: { daemonId: host.daemonId },
        results: [{ name: 'initial', testSummary: manifest.expectedInitial },
            { name: 'reference', testSummary: manifest.expectedReference }] };
    const publication = await publicationFromSnapshot(directory, receipt, host);
    assert.deepEqual(Object.keys(publication.initialFiles).sort(), ['pom.xml', 'src/main/java/interview/CartTotal.java']);
    assert.deepEqual(Object.keys(publication.acceptanceFiles), ['CartTotalTest.java']);
    assert.equal(publication.identity.package_sha256, packageSha256);
    assert.equal(publication.identity.execution_image_digest, imageId);
    assert(!JSON.stringify(publication).includes('reference/src'));
    const altered = structuredClone(publication);
    altered.initialFiles['src/main/java/interview/CartTotal.java'] = 'not compilable';
    assert.throws(() => verifyPublicationInputs(altered), /Published inputs/);
    verifyPublicationInputs(publication);
    await assert.rejects(publicationFromSnapshot(directory, { ...receipt, publicationInputsSha256: '0'.repeat(64) }, host), /Published inputs/);
    await assert.rejects(publicationFromSnapshot(directory, { ...receipt, packageSha256: 'b'.repeat(64) }, host));
    await assert.rejects(publicationFromSnapshot(directory, receipt, { ...host, daemonId: 'another-host' }));
    await assert.rejects(publicationFromSnapshot(directory, receipt, { ...host, imageId: `sha256:${'b'.repeat(64)}` }));
});
