import { object } from '../api/request';

export interface Draft {
  owner: string; exercise: string; path: string; id: string;
  revision: string; baseContent: string; content: string; updatedAt: number;
}
function valid(value: unknown): value is Draft {
  return object(value) && ['owner', 'exercise', 'path', 'id', 'revision', 'baseContent', 'content'].every((key) => typeof value[key] === 'string')
    && typeof value.updatedAt === 'number' && Number.isFinite(value.updatedAt) && typeof value.revision === 'string' && /^\d+$/.test(value.revision);
}
async function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('codecraft-workspace-drafts', 3);
    let blocked = false;
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains('drafts')) {
        const store = request.result.createObjectStore('drafts', { keyPath: ['owner', 'exercise', 'path', 'id'] });
        store.createIndex('file', ['owner', 'exercise', 'path']);
      }
      if (!request.result.objectStoreNames.contains('accounts')) request.result.createObjectStore('accounts', { keyPath: 'owner' });
      if (!request.result.objectStoreNames.contains('deleted')) request.result.createObjectStore('deleted', { keyPath: ['owner', 'exercise'] });
    };
    request.onerror = () => reject(new Error('Local draft storage unavailable'));
    request.onblocked = () => { blocked = true; reject(new Error('Local draft storage upgrade blocked')); };
    request.onsuccess = () => {
      if (blocked) { request.result.close(); return; }
      request.result.onversionchange = () => request.result.close(); resolve(request.result);
    };
  });
}
async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore, result: (value: T) => void, accounts: IDBObjectStore) => void): Promise<T> {
  const database = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = database.transaction(['drafts', 'accounts', 'deleted'], mode);
      let result: T;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(new Error('Local draft transaction failed'));
      tx.onerror = () => reject(new Error('Local draft transaction failed'));
      try { action(tx.objectStore('drafts'), (value) => { result = value; }, tx.objectStore('accounts')); }
      catch (error) { tx.abort(); reject(error); }
    });
  } finally { database.close(); }
}
function epoch(value: unknown): number {
  if (value === undefined) return 0;
  if (!object(value) || !Number.isSafeInteger(value.epoch) || typeof value.epoch !== 'number' || value.epoch < 0 || typeof value.blocked !== 'boolean') throw new Error('Invalid draft account state');
  return value.epoch;
}
export async function readDraftEpoch(owner: string): Promise<number> {
  return transaction<number>('readonly', (_store, result, accounts) => {
    const request = accounts.get(owner);
    request.onsuccess = () => {
      const value: unknown = request.result;
      try {
        if (object(value) && value.blocked === true) throw new Error('Draft account logged out');
        result(epoch(value));
      } catch { accounts.transaction.abort(); }
    };
  });
}
export async function resumeDrafts(owner: string): Promise<void> {
  await transaction<void>('readwrite', (_store, result, accounts) => {
    const request = accounts.get(owner);
    request.onsuccess = () => {
      try { accounts.put({ owner, epoch: epoch(request.result) + 1, blocked: false }); result(undefined); }
      catch { accounts.transaction.abort(); }
    };
  });
}
export async function putDraft(value: Draft, writerEpoch: number): Promise<void> {
  if (!valid(value)) throw new Error('Invalid local draft');
  await transaction<void>('readwrite', (store, result, accounts) => {
    const request = accounts.get(value.owner);
    request.onsuccess = () => {
      const account: unknown = request.result;
      try {
        if (epoch(account) !== writerEpoch || (object(account) && account.blocked === true)) throw new Error('Stale draft writer');
        const deleted = store.transaction.objectStore('deleted').get([value.owner, value.exercise]);
        deleted.onsuccess = () => {
          if (deleted.result !== undefined) { store.transaction.abort(); return; }
          store.put(value); result(undefined);
        };
      } catch { store.transaction.abort(); }
    };
  });
}
export async function listDrafts(owner: string, exercise: string, path: string): Promise<Draft[]> {
  return transaction<Draft[]>('readonly', (store, result) => {
    const request = store.index('file').getAll([owner, exercise, path]);
    request.onsuccess = () => {
      const values: unknown[] = request.result;
      result(values.filter(valid).sort((left, right) => right.updatedAt - left.updatedAt));
    };
  });
}
export async function listAccountDrafts(owner: string): Promise<Draft[]> {
  return transaction<Draft[]>('readonly', (store, result) => {
    const request = store.openCursor();
    const drafts: Draft[] = [];
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) { result(drafts.sort((left, right) => left.exercise.localeCompare(right.exercise) || left.path.localeCompare(right.path) || right.updatedAt - left.updatedAt)); return; }
      const value: unknown = cursor.value;
      if (valid(value) && value.owner === owner) drafts.push(value);
      cursor.continue();
    };
  });
}
// Check emptiness and fence all existing writers in the same IndexedDB transaction.
// Unlike explicit discard, this never deletes a draft that appeared during saving.
export async function sealDraftsForLogout(owner: string, expectedEpoch: number): Promise<boolean> {
  return transaction<boolean>('readwrite', (store, result, accounts) => {
    const account = accounts.get(owner);
    account.onsuccess = () => {
      const value: unknown = account.result;
      try {
        if (epoch(value) !== expectedEpoch || (object(value) && value.blocked === true)) { result(false); return; }
        const request = store.openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) { accounts.put({ owner, epoch: expectedEpoch + 1, blocked: true }); result(true); return; }
          const draft: unknown = cursor.value;
          if (object(draft) && draft.owner === owner) { result(false); return; }
          cursor.continue();
        };
      } catch { accounts.transaction.abort(); }
    };
  });
}
// Release only the seal owned by this attempt, never a later login/logout epoch.
export async function releaseDraftLogout(owner: string, sealedEpoch: number): Promise<boolean> {
  return transaction<boolean>('readwrite', (_store, result, accounts) => {
    const request = accounts.get(owner);
    request.onsuccess = () => {
      const value: unknown = request.result;
      try {
        if (epoch(value) !== sealedEpoch || !object(value) || value.blocked !== true) { result(false); return; }
        accounts.put({ owner, epoch: sealedEpoch + 1, blocked: false });
        result(true);
      } catch { accounts.transaction.abort(); }
    };
  });
}
export async function removeDraft(value: Draft): Promise<void> {
  await transaction<void>('readwrite', (store, result) => {
    const key = [value.owner, value.exercise, value.path, value.id];
    const request = store.get(key);
    request.onsuccess = () => {
      const current: unknown = request.result;
      if (valid(current) && current.content === value.content && current.updatedAt === value.updatedAt) store.delete(key);
      result(undefined);
    };
  });
}
// An irreversible, exercise-scoped fence also rejects delayed writers in other tabs.
// Login/account epoch changes never remove this deletion authority.
export async function confirmExerciseDeletion(owner: string, exercise: string): Promise<void> {
  await transaction<void>('readwrite', (store, result) => {
    store.transaction.objectStore('deleted').put({ owner, exercise });
    const request = store.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) { result(undefined); return; }
      const value: unknown = cursor.value;
      if (object(value) && value.owner === owner && value.exercise === exercise) cursor.delete();
      cursor.continue();
    };
  });
}

export async function clearDrafts(owner: string, exercise?: string): Promise<void> {
  await transaction<void>('readwrite', (store, result, accounts) => {
    if (exercise === undefined) {
      const account = accounts.get(owner);
      account.onsuccess = () => {
        try { accounts.put({ owner, epoch: epoch(account.result) + 1, blocked: true }); }
        catch { accounts.transaction.abort(); }
      };
    }
    const request = store.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) { result(undefined); return; }
      const value: unknown = cursor.value;
      if (valid(value) && value.owner === owner && (exercise === undefined || value.exercise === exercise)) cursor.delete();
      cursor.continue();
    };
  });
}
