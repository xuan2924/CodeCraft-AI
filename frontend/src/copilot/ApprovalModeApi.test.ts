import { afterEach, expect, it, vi } from 'vitest';
import { approvalMode, changeApprovalMode } from './api';

const owner = '10000000-0000-0000-0000-000000000001';
const exercise = '10000000-0000-0000-0000-000000000002';
const change = { exerciseId: exercise, id: '10000000-0000-0000-0000-000000000003', actorId: owner, previousMode: 'ASK', mode: 'AUTO', version: '1', createdAt: '2026-01-01T00:00:00Z' };
const current = { exerciseId: exercise, mode: 'AUTO', version: '1', history: [change] };
const signal = () => new AbortController().signal;
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
afterEach(() => vi.unstubAllGlobals());

it('validates current mode and bounded owner/exercise-scoped audit evidence', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response(current)));
  await expect(approvalMode(owner, exercise, signal())).resolves.toEqual(current);
  for (const invalid of [{ ...current, mode: 'SHELL' }, { ...current, version: '0' }, { ...current, version: '01' },
    { ...current, exerciseId: owner }, { ...current, history: [change, change] }, { ...current, history: Array(21).fill(change) },
    { ...current, history: [{ ...change, actorId: exercise }] }, { ...current, history: [{ ...change, exerciseId: owner }] },
    { ...current, history: [] }, { ...current, history: [{ ...change, createdAt: 'invalid' }] }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response(invalid)));
    await expect(approvalMode(owner, exercise, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});

it('uses shared mutation transport with operation credential, owner, captured version and original key', async () => {
  const fetcher = vi.fn<typeof fetch>(async (path) => response(String(path).endsWith('/csrf') ? { token: 'csrf' } : change));
  vi.stubGlobal('fetch', fetcher);
  await expect(changeApprovalMode(owner, exercise, 'control', 'AUTO', '0', 'original-request', signal())).resolves.toEqual(change);
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', cache: 'no-store', headers: {
    'X-Codecraft-Owner': owner, 'X-Operation-Credential': 'control', 'Idempotency-Key': 'original-request', 'X-CSRF-TOKEN': 'csrf',
  }, body: JSON.stringify({ mode: 'AUTO', expectedVersion: '0' }) });
});

it('does not accept a successful response for a different mode or command version', async () => {
  for (const invalid of [{ ...change, mode: 'ASK' }, { ...change, version: '2' }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path) => response(String(path).endsWith('/csrf') ? { token: 'csrf' } : invalid)));
    await expect(changeApprovalMode(owner, exercise, 'control', 'AUTO', '0', 'original-request', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
