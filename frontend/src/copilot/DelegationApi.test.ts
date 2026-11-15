import { beforeEach, expect, it, vi } from 'vitest';
import { request } from '../api/request';
import { list } from './delegationApi';
vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), request: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001'; const parent = '10000000-0000-0000-0000-000000000002';
const child = '10000000-0000-0000-0000-000000000003'; const conversation = '10000000-0000-0000-0000-000000000004';
const row = { toolId: '10000000-0000-0000-0000-000000000005', parentTaskId: parent, conversationId: conversation, goal: 'Child goal', state: 'WAITING',
  child: { id: child, conversationId: conversation, exerciseId: exercise, state: 'WAITING_APPROVAL', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: null },
  resultPayload: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const signal = new AbortController().signal;
beforeEach(() => vi.resetAllMocks());
it('only reads the bounded parent relation and validates child exercise/conversation ownership', async () => {
  vi.mocked(request).mockResolvedValue([row]); await expect(list('owner', exercise, parent, signal)).resolves.toEqual([row]);
  expect(request).toHaveBeenCalledWith(`/api/copilot-tasks/${parent}/delegations`, signal, { headers: { 'X-Codecraft-Owner': 'owner' } });
  for (const bad of [{ ...row, parentTaskId: child }, { ...row, child: { ...row.child, exerciseId: child } },
    { ...row, conversationId: exercise }, { ...row, child: { ...row.child, id: parent } }, { ...row, resultPayload: 'not yet committed' }, { ...row, state: 'RESOLVED' }]) {
    vi.mocked(request).mockResolvedValue([bad]); await expect(list('owner', exercise, parent, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
  vi.mocked(request).mockResolvedValue([row, row]); await expect(list('owner', exercise, parent, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('preserves an explicit failure handoff rather than treating it as child success', async () => {
  const complete = { ...row, state: 'RESOLVED', resultPayload: '{"state":"INTERRUPTED","reason":"MODEL_RESULT_UNKNOWN"}', completedAt: row.createdAt,
    child: { ...row.child, state: 'INTERRUPTED', reason: 'MODEL_RESULT_UNKNOWN', completedAt: row.createdAt } };
  vi.mocked(request).mockResolvedValue([complete]); await expect(list('owner', exercise, parent, signal)).resolves.toEqual([complete]);
});
