import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, cp, readFile, writeFile, rm, symlink, link } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validatePackage } from './problem-contract.mjs';

const source = new URL('../problems/cart-total/1.0.0/', import.meta.url);

async function fixture(t) {
    const directory = await mkdtemp(join(tmpdir(), 'cart-contract-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    await cp(source, directory, { recursive: true });
    return directory;
}

test('published cart package has a fixed version and protected edit boundaries', async () => {
    const result = await validatePackage(source);
    assert.equal(result.manifest.version, '1.0.0');
    assert.match(result.packageSha256, /^[a-f0-9]{64}$/);
});

test('rejects an editable root that escapes the workspace', async (t) => {
    const directory = await fixture(t);
    const file = join(directory, 'manifest.json');
    const manifest = JSON.parse(await readFile(file, 'utf8'));
    manifest.editableRoots.push('../other-exercise');
    await writeFile(file, JSON.stringify(manifest));
    await assert.rejects(validatePackage(directory), /editableRoots/);
});

test('rejects even internal directory links', async (t) => {
    const directory = await fixture(t);
    await symlink(join(directory, 'initial'), join(directory, 'linked'),
        process.platform === 'win32' ? 'junction' : 'dir');
    await assert.rejects(validatePackage(directory), /Links forbidden/);
});

test('rejects hardlinks rather than sharing file identity', async (t) => {
    const directory = await fixture(t);
    await link(join(directory, 'README.md'), join(directory, 'alias.md'));
    await assert.rejects(validatePackage(directory), /hardlinked/);
});

test('rejects missing authority tests before building an image', async (t) => {
    const directory = await fixture(t);
    await rm(join(directory, 'acceptance/CartTotalTest.java'));
    await assert.rejects(validatePackage(directory), /Missing acceptance/);
});

test('rejects unbounded execution settings', async (t) => {
    const directory = await fixture(t);
    const file = join(directory, 'manifest.json');
    const manifest = JSON.parse(await readFile(file, 'utf8'));
    manifest.runtime.timeoutSeconds = 0;
    await writeFile(file, JSON.stringify(manifest));
    await assert.rejects(validatePackage(directory), /runtime.timeoutSeconds/);
});

test('rejects an initial workspace beyond 200 files before publication', async (t) => {
    const directory = await fixture(t);
    for (let index = 0; index < 199; index++) {
        await writeFile(join(directory, 'initial', 'src', 'main', 'java', 'interview', `Extra${index}.java`), 'class Extra {}');
    }
    await assert.rejects(validatePackage(directory), /Initial workspace file limit/);
});

test('rejects an initial workspace beyond 10 MiB without truncating its files', async (t) => {
    const directory = await fixture(t);
    const content = 'a'.repeat(1024 * 1024);
    for (let index = 0; index < 10; index++) {
        await writeFile(join(directory, 'initial', 'src', 'main', 'java', 'interview', `Large${index}.java`), content);
    }
    await assert.rejects(validatePackage(directory), /Initial workspace content limit/);
    assert.equal((await readFile(join(directory, 'initial', 'src', 'main', 'java', 'interview', 'Large0.java'))).length, 1024 * 1024);
});

test('content changes alter package identity without changing the semantic version', async (t) => {
    const directory = await fixture(t);
    const before = await validatePackage(directory);
    await writeFile(join(directory, 'README.md'), 'Different requirements\n');
    const after = await validatePackage(directory);
    assert.notEqual(after.packageSha256, before.packageSha256);
});
