import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';

function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value !== null && typeof value === 'object') {
        return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
    }
    return value;
}
export function publicationInputsSha256(value) {
    return createHash('sha256').update(JSON.stringify(canonical({ manifest: value.manifest, requirements: value.requirements,
        initialFiles: value.initialFiles, acceptanceFiles: value.acceptanceFiles }))).digest('hex');
}
export function verifyPublicationInputs(value) {
    assert.equal(publicationInputsSha256(value), value.receipt?.publicationInputsSha256,
        'Published inputs differ from the validated receipt');
}
export async function readPublicationInputs(directory) {
    async function text(path) { return new TextDecoder('utf-8', { fatal: true }).decode(await readFile(join(directory, path))); }
    async function files(subdirectory) {
        const pairs = [];
        async function visit(relative) {
            for (const entry of await readdir(join(directory, subdirectory, relative), { withFileTypes: true })) {
                const path = relative ? `${relative}/${entry.name}` : entry.name;
                if (entry.isDirectory()) await visit(path);
                else pairs.push([path, await text(`${subdirectory}/${path}`)]);
            }
        }
        await visit('');
        return Object.fromEntries(pairs);
    }
    return { manifest: JSON.parse(await text('manifest.json')), requirements: await text('README.md'),
        initialFiles: await files('initial'), acceptanceFiles: await files('acceptance') };
}
