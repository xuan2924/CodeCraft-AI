import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { basename } from 'node:path';

// Real Docker integration at the maintainer publication boundary. Inject only
// filesystem cleanup failure; never replace container results with fake success.
test('cleanup failure prevents a successful publication receipt', async (t) => {
    const originalRemove = fs.rm;
    let failedDirectory;
    t.after(async () => {
        fs.rm = originalRemove;
        syncBuiltinESMExports();
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
    await assert.rejects(fs.access(new URL('../problems/.validation/receipt.json', import.meta.url)),
        { code: 'ENOENT' });
});
