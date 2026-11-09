import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run } from './process.mjs';
import { publicationFromSnapshot } from './publication-contract.mjs';

// Revalidation is mandatory; an old receipt or mutable tag is never publication authority.
run(process.execPath, ['scripts/validate-problem.mjs']);
await mkdir(join(root, '.runtime'), { recursive: true });
const temporary = await mkdtemp(join(root, '.runtime', 'publication-'));
try {
    const snapshot = join(temporary, 'package');
    await cp(join(root, 'problems/cart-total/1.0.0'), snapshot, { recursive: true });
    const receipt = JSON.parse(await readFile(join(root, 'problems/.validation/receipt.json'), 'utf8'));
    const docker = (args) => execFileSync('docker', args, { encoding: 'utf8', timeout: 15000, maxBuffer: 1048576 }).trim();
    const image = JSON.parse(docker(['image', 'inspect', '--format', '{{json .}}', receipt.imageId]));
    const publication = await publicationFromSnapshot(snapshot, receipt, {
        daemonId: docker(['info', '--format', '{{.ID}}']), imageId: image.Id, platform: `${image.Os}/${image.Architecture}`,
    });
    const destination = join(root, '.runtime', 'problem-publication.json');
    let existing;
    try { existing = JSON.parse(await readFile(destination, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (existing) {
        assert.deepEqual(existing.identity, publication.identity, 'Published id/version is immutable; use a new version');
        // Keep the original immutable artifact; the fresh receipt remains in .validation.
    } else {
        await writeFile(destination, JSON.stringify(publication, null, 2) + '\n', { flag: 'wx' });
    }
    console.log(`Local publication artifact: ${destination}`);
    console.log('Local Docker image ID only; this is not a registry digest or cross-host publication.');
} finally { await rm(temporary, { recursive: true, force: true }); }
