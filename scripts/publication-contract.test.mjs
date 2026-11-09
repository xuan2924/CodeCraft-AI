import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { validatePackage } from './problem-contract.mjs';
import { publicationFromSnapshot } from './publication-contract.mjs';
import { publicationInputsSha256, readPublicationInputs, verifyPublicationInputs } from './publication-inputs.mjs';

const directory = fileURLToPath(new URL('../problems/cart-total/1.0.0/', import.meta.url));

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
