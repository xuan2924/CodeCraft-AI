import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, cp, readFile, writeFile, rm, symlink, link } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validatePackage } from './problem-contract.mjs';

const source = new URL('../problems/cart-total/1.1.0/', import.meta.url);
const orderSettlementSource = new URL('../problems/order-settlement-engine/1.0.0/', import.meta.url);

async function fixture(t, packageUrl = source) {
    const directory = await mkdtemp(join(tmpdir(), 'problem-contract-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    await cp(packageUrl, directory, { recursive: true });
    return directory;
}

test('published cart package has a fixed version and protected edit boundaries', async () => {
    const result = await validatePackage(source);
    assert.equal(result.manifest.version, '1.1.0');
    assert(result.manifest.execution, 'default package supports controlled tests');
    assert.equal(result.manifest.challenge, 'challenge');
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

test('challenge cannot silently replace initial code or dependencies', async (t) => {
    const directory = await fixture(t);
    await writeFile(join(directory, 'challenge', 'initial', 'pom.xml'), 'different dependencies');
    await assert.rejects(validatePackage(directory), /Challenge initial files/);
});

test('challenge package is bounded to exactly one round', async (t) => {
    const directory = await fixture(t);
    const manifest = JSON.parse(await readFile(join(directory, 'challenge', 'manifest.json'), 'utf8'));
    manifest.challenge = 'challenge';
    await writeFile(join(directory, 'challenge', 'manifest.json'), JSON.stringify(manifest));
    await assert.rejects(validatePackage(directory), /Nested challenge/);
});

test('content changes alter package identity without changing the semantic version', async (t) => {
    const directory = await fixture(t);
    const before = await validatePackage(directory);
    await writeFile(join(directory, 'README.md'), 'Different requirements\n');
    const after = await validatePackage(directory);
    assert.notEqual(after.packageSha256, before.packageSha256);
});

test('multi-file order-settlement-engine package satisfies v1 contract, multi-level AGENTS.md, and challenge rules', async () => {
    const base = await validatePackage(orderSettlementSource);
    assert.equal(base.manifest.id, 'order-settlement-engine');
    assert.equal(base.manifest.version, '1.0.0');
    assert.equal(base.manifest.challenge, 'challenge');
    assert.equal(base.manifest.execution.suite, 'settlement.OrderSettlementEngineTest');
    assert.equal(base.manifest.execution.tests.length, 10);
    assert.deepEqual(base.manifest.expectedInitial, { tests: 10, failures: 7, errors: 0, skipped: 0 });
    assert.deepEqual(base.manifest.expectedReference, { tests: 10, failures: 0, errors: 0, skipped: 0 });
    assert.match(base.packageSha256, /^[a-f0-9]{64}$/);

    const challenge = await validatePackage(new URL('../problems/order-settlement-engine/1.0.0/challenge/', import.meta.url), true);
    assert.equal(challenge.manifest.id, 'order-settlement-engine-challenge');
    assert.equal(challenge.manifest.execution.tests.length, 15);
    assert.deepEqual(challenge.manifest.expectedInitial, { tests: 15, failures: 12, errors: 0, skipped: 0 });
    assert.deepEqual(challenge.manifest.expectedReference, { tests: 15, failures: 0, errors: 0, skipped: 0 });

    const rootAgents = await readFile(new URL('../problems/order-settlement-engine/1.0.0/initial/AGENTS.md', import.meta.url), 'utf8');
    const pricingAgents = await readFile(new URL('../problems/order-settlement-engine/1.0.0/initial/src/main/java/settlement/pricing/AGENTS.md', import.meta.url), 'utf8');
    assert(rootAgents.includes('BigInteger') && pricingAgents.includes('Largest-Remainder'));

    for (const rel of [
        'model/OrderLine.java',
        'model/CouponSpec.java',
        'model/SettlementReceipt.java',
        'pricing/DiscountAllocator.java',
        'idempotency/SettlementLedger.java',
        'OrderSettlementEngine.java',
    ]) {
        await readFile(new URL(`../problems/order-settlement-engine/1.0.0/initial/src/main/java/settlement/${rel}`, import.meta.url), 'utf8');
        await readFile(new URL(`../problems/order-settlement-engine/1.0.0/reference/src/main/java/settlement/${rel}`, import.meta.url), 'utf8');
        await readFile(new URL(`../problems/order-settlement-engine/1.0.0/challenge/reference/src/main/java/settlement/${rel}`, import.meta.url), 'utf8');
    }
});

test('order-settlement-engine rejects mismatched challenge AGENTS.md, missing acceptance test, or missing Java sources', async (t) => {
    const dir1 = await fixture(t, orderSettlementSource);
    await writeFile(join(dir1, 'challenge', 'initial', 'src', 'main', 'java', 'settlement', 'pricing', 'AGENTS.md'), 'tampered');
    await assert.rejects(validatePackage(dir1), /Challenge initial files/);

    const dir2 = await fixture(t, orderSettlementSource);
    await rm(join(dir2, 'acceptance', 'OrderSettlementEngineTest.java'));
    await assert.rejects(validatePackage(dir2), /Missing acceptance\/OrderSettlementEngineTest\.java/);

    const dir3 = await fixture(t, orderSettlementSource);
    await rm(join(dir3, 'reference', 'src'), { recursive: true, force: true });
    await assert.rejects(validatePackage(dir3), /Missing Java sources under reference\/src\/main\/java/);
});
