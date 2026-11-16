import assert from 'node:assert/strict';
import { readPublicationInputs, verifyPublicationInputs } from './publication-inputs.mjs';
import { validatePackage } from './problem-contract.mjs';
import { join } from 'node:path';

// Input is a private maintainer snapshot, never an active user workspace.
export async function publicationFromSnapshot(directory, receipt, host) {
    const { manifest, packageSha256 } = await validatePackage(directory);
    assert.equal(receipt.verdict, 'local-contract-validation-passed');
    assert.equal(receipt.packageSha256, packageSha256, 'Package changed after validation');
    assert.match(receipt.imageId, /^sha256:[0-9a-f]{64}$/);
    assert.equal(receipt.imageId, host.imageId);
    assert.equal(receipt.platform, host.platform);
    assert.equal(host.platform, 'linux/amd64');
    assert(host.daemonId && host.daemonId === receipt.environment.daemonId, 'Validate on this daemon first');
    assert.deepEqual(receipt.limits, manifest.runtime);
    for (const variant of ['initial', 'reference']) {
        assert.deepEqual(receipt.results.find((item) => item.name === variant)?.testSummary,
            manifest[variant === 'initial' ? 'expectedInitial' : 'expectedReference']);
    }
    if (manifest.challenge) {
        assert(receipt.challenge, 'Challenge requires its own successful offline validation receipt');
        assert(host.challenge, 'Challenge image must be present on the publication host');
        assert.notEqual(receipt.challenge.imageId, receipt.imageId, 'Challenge must use an independent image');
        await publicationFromSnapshot(join(directory, 'challenge'), receipt.challenge, {
            ...host.challenge, daemonId: host.daemonId,
        });
    } else assert.equal(receipt.challenge, undefined, 'Unexpected challenge receipt');
    const inputs = await readPublicationInputs(directory);
    verifyPublicationInputs({ ...inputs, receipt });
    return {
        schemaVersion: 1,
        identity: { problem_id: manifest.id, version: manifest.version, package_sha256: packageSha256,
            execution_image_digest: receipt.imageId, platform: receipt.platform },
        ...inputs, receipt,
    };
}
