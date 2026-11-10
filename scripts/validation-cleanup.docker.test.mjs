import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { basename, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

// Real Docker integration at the maintainer publication boundary. Inject only
// filesystem cleanup failure; never replace container results with fake success.
test('cleanup failure prevents a successful publication receipt', async (t) => {
    const originalRemove = fs.rm;
    const originalArguments = process.argv;
    const evidence = await fs.mkdtemp(join(tmpdir(), 'publication-cleanup-test-'));
    process.argv = [process.execPath, 'validate-problem.mjs', fileURLToPath(new URL('../problems/cart-total/1.0.0/', import.meta.url)), evidence];
    let failedDirectory;
    t.after(async () => {
        fs.rm = originalRemove;
        process.argv = originalArguments;
        syncBuiltinESMExports();
        await originalRemove(evidence, { recursive: true, force: true });
        if (failedDirectory) await originalRemove(failedDirectory, { recursive: true, force: true });
    });
    fs.rm = async (path, options) => {
        if (typeof path === 'string' && basename(path).startsWith('cart-validation-')) {
            failedDirectory = path;
            throw new Error('Injected temporary-directory cleanup failure');
        }
        return originalRemove(path, options);
    };
    syncBuiltinESMExports();
    await assert.rejects(import('./validate-problem.mjs'), /Injected temporary-directory cleanup failure/);
    assert(failedDirectory, 'The cleanup failure point must actually be reached');
    await assert.rejects(fs.access(join(evidence, 'receipt.json')),
        { code: 'ENOENT' });
});
