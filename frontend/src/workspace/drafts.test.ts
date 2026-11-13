import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory, IDBCursor, IDBObjectStore } from 'fake-indexeddb';
import { confirmExerciseDeletion, listAccountDrafts, putDraft, readDraftEpoch, resumeDrafts, type Draft } from './drafts';

function draft(owner = 'owner', exercise = 'deleted', id = 'draft'): Draft {
  return { owner, exercise, id, path: 'Main.java', revision: '1', baseContent: 'base', content: 'private draft', updatedAt: 1 };
}
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('authoritative exercise deletion storage', () => {
  it('clears only the selected account/exercise and fences delayed writers after another login', async () => {
    await putDraft(draft(), 0);
    await putDraft(draft('owner', 'other'), 0);
    await putDraft(draft('other-owner'), 0);
    await confirmExerciseDeletion('owner', 'deleted');
    expect((await listAccountDrafts('owner')).map((value) => value.exercise)).toEqual(['other']);
    expect(await listAccountDrafts('other-owner')).toHaveLength(1);
    await expect(putDraft(draft('owner', 'deleted', 'late'), 0)).rejects.toThrow();
    await resumeDrafts('owner');
    const epoch = await readDraftEpoch('owner');
    await expect(putDraft(draft('owner', 'deleted', 'new-session'), epoch)).rejects.toThrow();
    await putDraft(draft('owner', 'other', 'new-session'), epoch);
    await confirmExerciseDeletion('owner', 'deleted');
    expect(await listAccountDrafts('owner')).toHaveLength(2);
  });

  it('rolls back both removed drafts and the tombstone if the transaction aborts', async () => {
    await putDraft(draft(), 0);
    const remove = IDBCursor.prototype.delete;
    vi.spyOn(IDBCursor.prototype, 'delete').mockImplementationOnce(function (this: IDBCursor) {
      const request = remove.call(this);
      const transaction = this.source instanceof IDBObjectStore ? this.source.transaction : this.source.objectStore.transaction;
      request.addEventListener('success', () => transaction.abort());
      return request;
    });
    await expect(confirmExerciseDeletion('owner', 'deleted')).rejects.toThrow();
    expect(await listAccountDrafts('owner')).toHaveLength(1);
    await putDraft(draft('owner', 'deleted', 'retry-write'), 0);
    expect(await listAccountDrafts('owner')).toHaveLength(2);
    await confirmExerciseDeletion('owner', 'deleted');
    expect(await listAccountDrafts('owner')).toEqual([]);
  });
});
