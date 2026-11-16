import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { validatePackage } from './problem-contract.mjs';
import { publicationFromSnapshot } from './publication-contract.mjs';
import { containerInputs } from './container-inputs.mjs';
import { publicationInputsSha256, readPublicationInputs, verifyPublicationInputs } from './publication-inputs.mjs';

const directory = fileURLToPath(new URL('../problems/cart-total/1.1.0/', import.meta.url));
const host = { daemonId: 'fixture-daemon', imageId: `sha256:${'a'.repeat(64)}`, platform: 'linux/amd64',
    challenge: { imageId: `sha256:${'b'.repeat(64)}`, platform: 'linux/amd64' } };

async function receipt(path, imageId) {
    const { manifest, packageSha256 } = await validatePackage(path);
    return { publicationInputsSha256: publicationInputsSha256(await readPublicationInputs(path)),
        verdict: 'local-contract-validation-passed', packageSha256, imageId,
        platform: host.platform, limits: manifest.runtime, environment: { daemonId: host.daemonId },
        results: [{ name: 'initial', testSummary: manifest.expectedInitial }, { name: 'reference', testSummary: manifest.expectedReference }] };
}
async function verifiedReceipt() {
    return { ...await receipt(directory, host.imageId), challenge: await receipt(`${directory}/challenge`, host.challenge.imageId) };
}

test('challenge requires independent validation and its hidden inputs remain bound to the parent receipt', async () => {
    const base = await receipt(directory, host.imageId);
    await assert.rejects(publicationFromSnapshot(directory, base, host), /Challenge requires/);
    base.challenge = await receipt(`${directory}/challenge`, host.challenge.imageId);
    const publication = await publicationFromSnapshot(directory, base, host);
    assert.equal(publication.receipt.challenge.imageId, host.challenge.imageId);
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
    await assert.rejects(publicationFromSnapshot(directory, invalid, host));
});

test('publication binds verification to package, immutable image and local daemon; excludes reference', async () => {
    const receipt = await verifiedReceipt();
    const publication = await publicationFromSnapshot(directory, receipt, host);
    assert.deepEqual(Object.keys(publication.initialFiles).sort(), ['pom.xml', 'src/main/java/interview/CartTotal.java']);
    assert.deepEqual(Object.keys(publication.acceptanceFiles), ['CartTotalTest.java']);
    assert.equal(publication.identity.package_sha256, receipt.packageSha256);
    assert.equal(publication.identity.execution_image_digest, host.imageId);
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
