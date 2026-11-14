import { afterEach, expect, it, vi } from 'vitest';
import { sendSteer, steers } from './api';

const owner = '10000000-0000-0000-0000-000000000001';
const exercise = '10000000-0000-0000-0000-000000000002';
const task = '10000000-0000-0000-0000-000000000003';
const received = { id: '10000000-0000-0000-0000-000000000004', sequence: '10', taskId: task, exerciseId: exercise, actorId: owner,
  text: 'Keep the public API', state: 'RECEIVED', turnId: null, reason: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const signal = () => new AbortController().signal;
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
afterEach(() => vi.unstubAllGlobals());

it('validates owner/task scope, numeric ordering and explicit dispatch states', async () => {
  const older = { ...received, id: '10000000-0000-0000-0000-000000000005', sequence: '9' };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response({ items: [received, older], nextCursor: null })));
  await expect(steers(owner, exercise, task, '0', signal())).resolves.toMatchObject({ items: [received, older] });
  for (const invalid of [{ ...received, actorId: exercise }, { ...received, taskId: exercise }, { ...received, exerciseId: owner },
    { ...received, state: 'APPLIED' }, { ...received, state: 'NOT_APPLIED' }, { ...received, sequence: '0' },
    { ...received, reason: 'NOT_EXECUTED' }, { ...received, text: '中'.repeat(1366) }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response({ items: [invalid], nextCursor: null })));
    await expect(steers(owner, exercise, task, '0', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
  for (const items of [[older, received], [received, received], Array(21).fill(received)]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => response({ items, nextCursor: null })));
    await expect(steers(owner, exercise, task, '0', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});

it('binds the original text, key and operation credential through the common transport', async () => {
  const fetcher = vi.fn<typeof fetch>(async (path) => response(String(path).endsWith('/csrf') ? { token: 'csrf' } : received));
  vi.stubGlobal('fetch', fetcher);
  await expect(sendSteer(owner, exercise, task, 'control', received.text, 'original', signal())).resolves.toEqual(received);
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', cache: 'no-store', body: JSON.stringify({ text: received.text }), headers: {
    'X-Codecraft-Owner': owner, 'X-Operation-Credential': 'control', 'Idempotency-Key': 'original', 'X-CSRF-TOKEN': 'csrf',
  } });
  await expect(sendSteer(owner, exercise, task, 'control', 'Different instruction', 'original', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
