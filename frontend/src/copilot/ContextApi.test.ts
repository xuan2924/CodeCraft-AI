import { afterEach, expect, it, vi } from 'vitest';
import { cancelCompaction, compact, context } from './api';
import { ApiError } from '../api/request';
import { commandPolicy } from './errors';

it('distinguishes known compaction refusals from an unknown dispatch result', () => {
  expect(commandPolicy.definitiveRejection(new ApiError(409, 'COPILOT_COMPACTION_BUSY'))).toBe(true);
  expect(commandPolicy.definitiveRejection(new ApiError(404, 'COPILOT_COMPACTION_NOT_FOUND'))).toBe(true);
  expect(commandPolicy.definitiveRejection(new ApiError(503, 'COPILOT_STORAGE_UNAVAILABLE'))).toBe(false);
});

const owner = '10000000-0000-0000-0000-000000000001';
const exercise = '10000000-0000-0000-0000-000000000002';
const id = '10000000-0000-0000-0000-000000000003';
const waiting = { id, sequence: '10', exerciseId: exercise, trigger: 'MANUAL', state: 'WAITING', reason: null, attempts: 0,
  stopRequested: false, sourceVersion: null, throughSequence: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const empty = { exerciseId: exercise, version: '0', summaryId: null, throughSequence: '0', summary: null, requests: [] };
const signal = () => new AbortController().signal;
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
afterEach(() => vi.unstubAllGlobals());

it('checks exercise scope, published-view consistency, bounded ordered receipts and terminal states', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response({ ...empty, requests: [waiting] })));
  await expect(context(owner, exercise, signal())).resolves.toMatchObject({ requests: [waiting] });
  for (const invalid of [{ ...empty, exerciseId: owner }, { ...empty, summary: 'unpublished' }, { ...empty, version: '1' },
    { ...empty, requests: [waiting, waiting] }, { ...empty, requests: [{ ...waiting, state: 'APPLIED' }] },
    { ...empty, requests: [{ ...waiting, attempts: 3 }] }, { ...empty, requests: [{ ...waiting, sequence: '0' }] },
    { ...empty, requests: [{ ...waiting, exerciseId: owner }] }, { ...empty, requests: [{ ...waiting, state: 'UNKNOWN' }] },
    { ...empty, version: '1', summaryId: id, throughSequence: '20', summary: '中'.repeat(11000) }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response(invalid)));
    await expect(context(owner, exercise, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});

it('uses the common owner/CSRF transport and binds cancel responses to the requested record', async () => {
  const fetcher = vi.fn<typeof fetch>(async (path) => response(String(path).endsWith('/csrf') ? { token: 'csrf' } : waiting));
  vi.stubGlobal('fetch', fetcher);
  await expect(compact(owner, exercise, 'control', 'original', signal())).resolves.toEqual(waiting);
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', cache: 'no-store', body: JSON.stringify({ feeAcknowledged: true }), headers: {
    'X-Codecraft-Owner': owner, 'X-Operation-Credential': 'control', 'Idempotency-Key': 'original', 'X-CSRF-TOKEN': 'csrf',
  } });
  await expect(cancelCompaction(owner, exercise, id, 'stop', signal())).resolves.toEqual(waiting);
  await expect(cancelCompaction(owner, exercise, owner, 'stop', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
