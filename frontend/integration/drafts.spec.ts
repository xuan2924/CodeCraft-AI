import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('/integration/index.html'); });

test('independent drafts retain conflicts, sort by recency and isolate owners', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const base = { owner: 'alice', exercise: 'one', path: 'Main.java', id: 'page-a', revision: '0', baseContent: 'OLD', content: 'NEW', updatedAt: 1 };
    await drafts.putDraft(base, 0);
    await drafts.putDraft({ ...base, id: 'page-b', content: 'CONFLICT', updatedAt: 2 }, 0);
    await drafts.putDraft({ ...base, owner: 'bob' }, 0);
    await drafts.clearDrafts('bob');
    return { file: await drafts.listDrafts('alice', 'one', 'Main.java'), account: await drafts.listAccountDrafts('alice'), bob: await drafts.listAccountDrafts('bob') };
  });
  expect(result.file.map((draft) => draft.content)).toEqual(['CONFLICT', 'NEW']);
  expect(result.account).toHaveLength(2);
  expect(result.bob).toEqual([]);
});

test('stale removal cannot delete a newer draft with the same identity', async ({ page }) => {
  expect(await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const old = { owner: 'alice', exercise: 'one', path: 'Main.java', id: 'page', revision: '0', baseContent: '', content: 'OLD', updatedAt: 1 };
    await drafts.putDraft(old, 0);
    await drafts.putDraft({ ...old, content: 'NEW', updatedAt: 2 }, 0);
    await drafts.removeDraft(old);
    return (await drafts.listAccountDrafts('alice')).map((draft) => draft.content);
  })).toEqual(['NEW']);
});

test('logout sealing observes a draft arriving after the preliminary list', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const before = await drafts.listAccountDrafts('alice');
    await drafts.putDraft({ owner: 'alice', exercise: 'one', path: 'Main.java', id: 'late', revision: '0', baseContent: '', content: 'retain me', updatedAt: 1 }, 0);
    const sealed = await drafts.sealDraftsForLogout('alice', 0);
    return { before, sealed, after: await drafts.listAccountDrafts('alice') };
  });
  expect(result.before).toEqual([]);
  expect(result.sealed).toBe(false);
  expect(result.after[0]?.content).toBe('retain me');
});

test('final cursor check cannot seal over a write entering the same transaction', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const original = IDBObjectStore.prototype.openCursor;
    let injected = false;
    try {
      IDBObjectStore.prototype.openCursor = function (...args) {
        if (this.name === 'drafts' && this.transaction.mode === 'readwrite') {
          IDBObjectStore.prototype.openCursor = original;
          injected = true;
          this.put({ owner: 'alice', exercise: 'one', path: 'Main.java', id: 'late', revision: '0', baseContent: '', content: 'late transaction', updatedAt: 1 });
        }
        return original.apply(this, args);
      };
      const sealed = await drafts.sealDraftsForLogout('alice', 0);
      return { injected, sealed, after: await drafts.listAccountDrafts('alice'), epoch: await drafts.readDraftEpoch('alice') };
    } finally { IDBObjectStore.prototype.openCursor = original; }
  });
  expect(result).toMatchObject({ injected: true, sealed: false, epoch: 0 });
  expect(result.after[0]?.content).toBe('late transaction');
});

test('atomic seal fences stale writers and old logout cannot affect a new login epoch', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const value = { owner: 'alice', exercise: 'one', path: 'Main.java', id: 'page', revision: '0', baseContent: '', content: 'NEW LOGIN', updatedAt: 1 };
    const sealed = await drafts.sealDraftsForLogout('alice', 0);
    const staleRejected = await drafts.putDraft(value, 0).then(() => false, () => true);
    await drafts.resumeDrafts('alice');
    const epoch = await drafts.readDraftEpoch('alice');
    await drafts.putDraft(value, epoch);
    const released = await drafts.releaseDraftLogout('alice', 1);
    return { sealed, staleRejected, released, after: await drafts.listAccountDrafts('alice') };
  });
  expect(result).toMatchObject({ sealed: true, staleRejected: true, released: false });
  expect(result.after[0]?.content).toBe('NEW LOGIN');
});

test('failed IndexedDB write aborts without replacing the committed draft', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const value = { owner: 'alice', exercise: 'one', path: 'Main.java', id: 'page', revision: '0', baseContent: '', content: 'COMMITTED', updatedAt: 1 };
    await drafts.putDraft(value, 0);
    const put = IDBObjectStore.prototype.put;
    let rejected;
    try {
      IDBObjectStore.prototype.put = function (...args) {
        if (this.name === 'drafts') throw new DOMException('Injected quota exhaustion', 'QuotaExceededError');
        return put.apply(this, args);
      };
      rejected = await drafts.putDraft({ ...value, content: 'UNCOMMITTED' }, 0).then(() => false, () => true);
    } finally { IDBObjectStore.prototype.put = put; }
    return { rejected, after: await drafts.listAccountDrafts('alice') };
  });
  expect(result.rejected).toBe(true);
  expect(result.after[0]?.content).toBe('COMMITTED');
});

test('exercise cleanup traverses cursors without clearing another exercise or fencing the account', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const module = '/src/workspace/drafts.ts';
    const drafts: typeof import('../src/workspace/drafts') = await import(module);
    const value = { owner: 'alice', exercise: 'one', path: 'Main.java', id: 'page', revision: '0', baseContent: '', content: 'draft', updatedAt: 1 };
    await drafts.putDraft(value, 0);
    await drafts.putDraft({ ...value, exercise: 'two' }, 0);
    await drafts.clearDrafts('alice', 'one');
    return { epoch: await drafts.readDraftEpoch('alice'), after: await drafts.listAccountDrafts('alice') };
  });
  expect(result.epoch).toBe(0);
  expect(result.after.map((draft) => draft.exercise)).toEqual(['two']);
});
