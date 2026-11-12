import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Maintainer-side v1 package gate, not a runtime workspace authorization API.
export async function validatePackage(directory) {
    const root = directory instanceof URL ? fileURLToPath(directory) : directory;
    const hash = createHash('sha256');
    const files = new Set();
    let initialFiles = 0;
    let initialBytes = 0;
    async function visit(relative) {
        const absolute = join(root, relative);
        const stat = await lstat(absolute);
        assert(!stat.isSymbolicLink(), `Links forbidden: ${relative}`);
        if (stat.isDirectory()) {
            for (const name of (await readdir(absolute)).sort()) {
                assert(/^[a-zA-Z0-9_.-]+$/.test(name) && name !== '.' && name !== '..',
                    `Non-canonical path: ${name}`);
                await visit(relative ? `${relative}/${name}` : name);
            }
        } else {
            assert(stat.isFile() && stat.nlink === 1, `Special/hardlinked file: ${relative}`);
            assert(stat.size <= 1024 * 1024, `File too large: ${relative}`);
            if (relative.startsWith('initial/')) {
                initialFiles++;
                initialBytes += stat.size;
                assert(initialFiles <= 200, 'Initial workspace file limit exceeded');
                assert(initialBytes <= 10 * 1024 * 1024, 'Initial workspace content limit exceeded');
            }
            const data = await readFile(absolute);
            assert(!data.includes(0), `Binary file: ${relative}`);
            hash.update(JSON.stringify([relative, data.length]));
            hash.update(data);
            files.add(relative);
        }
    }
    await visit('');
    const manifest = JSON.parse(await readFile(join(root, 'manifest.json'), 'utf8'));
    assert.equal(manifest.schemaVersion, 1);
    assert.match(manifest.id, /^[a-z][a-z0-9-]*$/);
    assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
    assert(typeof manifest.title === 'string' && manifest.title.trim());
    assert(Number.isSafeInteger(manifest.durationSeconds) && manifest.durationSeconds > 0);
    for (const [key, value] of Object.entries({
        requirements: 'README.md', initial: 'initial', reference: 'reference',
        acceptance: 'acceptance', symlinks: 'forbidden',
    })) assert.equal(manifest[key], value, key);
    assert.deepEqual(manifest.editableRoots, ['src/main/java', 'src/test/java'], 'editableRoots');
    assert.deepEqual(manifest.targets, ['base']);
    if (manifest.execution !== undefined) {
        assert.match(manifest.execution.suite, /^[A-Za-z_][A-Za-z_0-9.]{0,254}$/);
        assert(Array.isArray(manifest.execution.tests) && manifest.execution.tests.length > 0 && manifest.execution.tests.length <= 200);
        assert.equal(new Set(manifest.execution.tests).size, manifest.execution.tests.length);
        for (const test of manifest.execution.tests) assert.match(test, /^[A-Za-z_][A-Za-z_0-9.]{0,254}$/);
        assert.equal(manifest.execution.tests.length, manifest.expectedReference.tests);
    }
    assert.equal(manifest.runtime.platform, 'linux/amd64');
    for (const key of ['cpus', 'memoryMiB', 'pids', 'workMiB', 'tmpMiB', 'shmMiB',
        'timeoutSeconds', 'logBytes', 'globalConcurrency']) {
        assert(Number.isSafeInteger(manifest.runtime[key]) && manifest.runtime[key] > 0,
            `Invalid runtime.${key}`);
    }
    for (const key of ['expectedInitial', 'expectedReference']) {
        const summary = manifest[key];
        for (const field of ['tests', 'failures', 'errors', 'skipped']) {
            assert(Number.isSafeInteger(summary[field]) && summary[field] >= 0, `${key}.${field}`);
        }
        assert(summary.tests > 0 && summary.failures + summary.errors + summary.skipped <= summary.tests);
    }
    assert.equal(manifest.expectedReference.failures + manifest.expectedReference.errors
        + manifest.expectedReference.skipped, 0);
    for (const required of ['README.md', 'Dockerfile', 'initial/pom.xml',
        'initial/src/main/java/interview/CartTotal.java',
        'reference/src/main/java/interview/CartTotal.java', 'acceptance/CartTotalTest.java']) {
        assert(files.has(required), `Missing ${required}`);
    }
    return { manifest, packageSha256: hash.digest('hex') };
}
