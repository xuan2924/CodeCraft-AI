import { beforeEach, expect, it, vi } from 'vitest';
import { post, request } from '../api/request';
import { cancel, enqueue, recent, resume } from './followupApi';
vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), request: vi.fn(), post: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const conversation = '10000000-0000-0000-0000-000000000002';
const id = '10000000-0000-0000-0000-000000000003';
const signal = new AbortController().signal;
const item = { id, sequence: '1', exerciseId: exercise, conversationId: conversation, text: 'next', state: 'WAITING', reason: null, taskId: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
beforeEach(() => vi.resetAllMocks());
it('binds original intent, owner, control and request key without starting or steering a task', async () => {
  vi.mocked(post).mockResolvedValue(item); await expect(enqueue('owner', exercise, conversation, 'control', 'next', 'original', signal)).resolves.toEqual(item);
  expect(post).toHaveBeenCalledWith(`/api/exercises/${exercise}/copilot/conversations/${conversation}/followups`, { text: 'next', feeAcknowledged: true }, signal, 'original', 'control', 'owner');
  vi.mocked(post).mockResolvedValue({ ...item, text: 'different' }); await expect(enqueue('owner', exercise, conversation, 'control', 'next', 'original', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('rejects cross-conversation, duplicated, unordered or inconsistent queued receipts', async () => {
  for (const items of [[{ ...item, conversationId: exercise }], [item, item], [{ ...item, state: 'DISPATCHED' }], [{ ...item, sequence: '2' }, { ...item, id: exercise }], [{ ...item, text: '中'.repeat(2000) }]]) {
    vi.mocked(request).mockResolvedValue(items); await expect(recent('owner', exercise, conversation, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
  vi.mocked(request).mockResolvedValue([item]); await expect(recent('owner', exercise, conversation, signal)).resolves.toEqual([item]);
});
it('checks exact cancellation and reauthorization identities', async () => {
  vi.mocked(post).mockResolvedValue({ ...item, state: 'CANCELLED', completedAt: item.createdAt }); await cancel('owner', exercise, conversation, id, 'cancel', signal);
  vi.mocked(post).mockResolvedValue({ conversationId: conversation, requestKey: 'resume' }); await resume('owner', exercise, conversation, 'control', 'resume', signal);
  vi.mocked(post).mockResolvedValue({ conversationId: conversation, requestKey: 'wrong' }); await expect(resume('owner', exercise, conversation, 'control', 'resume', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
