import { afterEach, expect, it, vi } from 'vitest';
import * as api from './commandApi';

const exercise = '10000000-0000-0000-0000-000000000001';
const workspace = '10000000-0000-0000-0000-000000000002';
const command = '10000000-0000-0000-0000-000000000003';
const execution = '10000000-0000-0000-0000-000000000004';
const snapshot: api.CommandTask = { id: command, exerciseId: exercise, workspaceInstanceId: workspace, executionInstanceId: execution,
  revision: '1', command: 'exit 7', directory: '.', state: 'EXITED', reason: 'command_exit', stopRequested: false, quiescent: true,
  synchronization: 'UNCHANGED', synchronizationReason: '', synchronizedRevision: null, changed: false, exitCode: 7,
  logs: [{ channel: 'stderr', text: 'ordinary failure' }], droppedBytes: '0', logsIncomplete: false,
  cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', stateVersion: '3', createdAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:00:01Z' };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
afterEach(() => vi.unstubAllGlobals());

it('accepts a nonzero command outcome but rejects grades, foreign scope and impossible synchronization', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(snapshot)));
  expect((await api.detail('owner', exercise, workspace, command, new AbortController().signal)).exitCode).toBe(7);
  for (const malformed of [{ ...snapshot, state: 'PASSED' }, { ...snapshot, exerciseId: workspace },
    { ...snapshot, completedAt: null }, { ...snapshot, synchronization: 'SYNCED' }, { ...snapshot, stateVersion: '9223372036854775808' },
    { ...snapshot, logs: [{ channel: 'unknown', text: 'bad' }] }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(malformed)));
    await expect(api.detail('owner', exercise, workspace, command, new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});

it('transmits the exact command, revision, owner and idempotency key through the shared request boundary', async () => {
  const fetcher = vi.fn<typeof fetch>(async (path, options) => {
    if (String(path).endsWith('/csrf')) return ok({ token: 'csrf' });
    expect(JSON.parse(String(options?.body))).toEqual({ command: 'exit 7', directory: '.', expectedRevision: '1' });
    const headers = new Headers(options?.headers);
    expect(headers.get('Idempotency-Key')).toBe('original-key');
    expect(headers.get('X-Codecraft-Owner')).toBe('owner');
    expect(headers.get('X-Operation-Credential')).toBe('current-control');
    return ok(snapshot);
  });
  vi.stubGlobal('fetch', fetcher);
  await api.start('owner', exercise, workspace, { command: 'exit 7', directory: '.', expectedRevision: '1' }, 'original-key', 'current-control', new AbortController().signal);
  expect(fetcher.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it('resolves a retired exact receipt without replaying or pretending its output still exists', async () => {
  const fetcher = vi.fn<typeof fetch>(async path => String(path).endsWith('/csrf') ? ok({ token: 'csrf' })
    : new Response(JSON.stringify({ code: 'COMMAND_HISTORY_PRUNED' }), { status: 410 }));
  vi.stubGlobal('fetch', fetcher);
  await expect(api.start('owner', exercise, workspace, { command: 'exit 7', directory: '.', expectedRevision: '1' }, 'same-key', 'control', new AbortController().signal))
    .resolves.toEqual({ retired: true });
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it('does not regress a stop intent or confirmed file synchronization with stale polling', () => {
  const current = { ...snapshot, stopRequested: true };
  const delayed = { ...snapshot, stateVersion: '2', state: 'RUNNING', quiescent: false, synchronization: 'PENDING' };
  expect(api.latest(current, delayed)).toEqual(current);
  expect(api.latest(current, { ...delayed, stateVersion: '3' })).toEqual(current);
});

it('validates retained diff identity and paths before exposing contents', async () => {
  const value = { commandId: command, exerciseId: exercise, workspaceInstanceId: workspace, executionInstanceId: execution, baseRevision: '1',
    changes: [{ kind: 'UPDATE', path: 'src/main/java/Main.java', previousContent: 'before', content: 'after' }] };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(value)));
  expect((await api.changes('owner', exercise, workspace, snapshot, new AbortController().signal)).changes).toHaveLength(1);
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok({ ...value, changes: [{ ...value.changes[0], path: '../other' }] })));
  await expect(api.changes('owner', exercise, workspace, snapshot, new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
