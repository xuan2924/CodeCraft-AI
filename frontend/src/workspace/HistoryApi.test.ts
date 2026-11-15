import { afterEach, expect, it, vi } from 'vitest';
import * as api from './historyApi';
const exercise = '10000000-0000-0000-0000-000000000001';
const id = '10000000-0000-0000-0000-000000000002';
const signal = () => new AbortController().signal;
const input: api.RestoreInput = { changeId: id, mode: 'UNDO', expectedRevision: '2', targetRevision: null, previewFingerprint: 'a'.repeat(64) };
const receipt = { exerciseId: exercise, operationId: id, state: 'SUCCEEDED', revision: '3', changed: true, failureCode: null };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
function response(value: unknown) { vi.stubGlobal('fetch', vi.fn<typeof fetch>(async path => String(path).endsWith('/csrf') ? ok({ token: 'csrf' }) : ok(value))); }
afterEach(() => vi.unstubAllGlobals());
it('accepts only an exact echoed original intent for explicit non-acceptance proof', async () => {
  const value = { exerciseId: exercise, requestKey: 'key', intent: input, operation: null, notAcceptedReason: 'REVISION_CONFLICT' };
  response(value);
  await expect(api.restore('owner', exercise, input, 'key', 'control', signal())).resolves.toEqual({ notAccepted: true, reason: 'REVISION_CONFLICT' });
  for (const malformed of [{ ...value, requestKey: 'other' }, { ...value, intent: { ...input, expectedRevision: '3' } }, { ...value, operation: receipt }, { ...value, notAcceptedReason: 'UNKNOWN' }]) {
    response(malformed);
    await expect(api.restore('owner', exercise, input, 'key', 'control', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
it('validates operation identity and resulting revision and preserves terminal high-water', async () => {
  response(receipt);
  const result = await api.operation('owner', exercise, id, '2', signal());
  expect(result.state).toBe('SUCCEEDED');
  expect(api.latest(result, { ...result, state: 'PENDING', revision: null, changed: null })).toEqual(result);
  for (const malformed of [{ ...receipt, revision: '4' }, { ...receipt, exerciseId: id }, { ...receipt, operationId: exercise }]) {
    response(malformed);
    await expect(api.operation('owner', exercise, id, '2', signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
it('rejects foreign history, nonmonotone revisions and duplicate entries', async () => {
  const change: api.CodeChange = { id, exerciseId: exercise, source: 'BASH', baseRevision: '1', resultRevision: '2', retained: true, taskId: null, conversationId: null, originChangeId: null, recordedAt: '2026-01-01T00:00:00Z' };
  response({ exerciseId: exercise, changes: [change], nextBefore: null });
  expect((await api.history('owner', exercise, null, signal())).changes).toEqual([change]);
  for (const changes of [[change, change], [{ ...change, exerciseId: id }], [{ ...change, resultRevision: '3' }]]) {
    response({ exerciseId: exercise, changes, nextBefore: null });
    await expect(api.history('owner', exercise, null, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
