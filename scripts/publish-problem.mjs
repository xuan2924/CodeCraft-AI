import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { root, run } from './process.mjs';
import { publicationFromSnapshot } from './publication-contract.mjs';

if (process.argv.length !== 2 && process.argv.length !== 5) {
    throw new Error('Usage: node scripts/publish-problem.mjs [package-directory evidence-directory publication-file]');
}
const packageDirectory = process.argv[2] ? resolve(process.argv[2]) : join(root, 'problems/cart-total/1.1.0');
const evidenceDirectory = process.argv[3] ? resolve(process.argv[3]) : join(root, 'problems/.validation');
const destination = process.argv[4] ? resolve(process.argv[4]) : join(root, '.runtime', 'problem-publication.json');
// Revalidation is mandatory; an old receipt or mutable tag is never publication authority.
run(process.execPath, ['scripts/validate-problem.mjs', packageDirectory, evidenceDirectory]);
await mkdir(join(root, '.runtime'), { recursive: true });
const temporary = await mkdtemp(join(root, '.runtime', 'publication-'));
try {
    const snapshot = join(temporary, 'package');
    await cp(packageDirectory, snapshot, { recursive: true });
    const receipt = JSON.parse(await readFile(join(evidenceDirectory, 'receipt.json'), 'utf8'));
    const docker = (args) => execFileSync('docker', args, { encoding: 'utf8', timeout: 15000, maxBuffer: 1048576 }).trim();
    let host;
    if (receipt.verdict !== 'static-contract-only') {
    const image = JSON.parse(docker(['image', 'inspect', '--format', '{{json .}}', receipt.imageId]));
    const challengeImage = receipt.challenge ? JSON.parse(docker(['image', 'inspect', '--format', '{{json .}}', receipt.challenge.imageId])) : null;
    host = {
        daemonId: docker(['info', '--format', '{{.ID}}']), imageId: image.Id, platform: `${image.Os}/${image.Architecture}`,
        ...(challengeImage ? { challenge: { imageId: challengeImage.Id, platform: `${challengeImage.Os}/${challengeImage.Architecture}` } } : {}),
    };
    }
    const publication = await publicationFromSnapshot(snapshot, receipt, host);
    let existing;
    try { existing = JSON.parse(await readFile(destination, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (existing) {
        assert.deepEqual(existing.identity, publication.identity, 'Published id/version is immutable; use a new version');
        assert.equal(existing.receipt?.challenge?.imageId, publication.receipt?.challenge?.imageId, 'Published challenge image is immutable; use a new version');
        // Keep the original immutable artifact; the fresh receipt remains in .validation.
    } else {
        await writeFile(destination, JSON.stringify(publication, null, 2) + '\n', { flag: 'wx' });
    }
    console.log(`Local publication artifact: ${destination}`);
    console.log(host ? 'Local Docker image ID only; not a cross-host publication.' : 'Static contract only; not executable.');
} finally { await rm(temporary, { recursive: true, force: true }); }
