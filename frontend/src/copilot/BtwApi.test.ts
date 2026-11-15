import { beforeEach, expect, it, vi } from 'vitest';
import { post, request } from '../api/request';
import { find, start } from './btwApi';
vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), post: vi.fn(), request: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001'; const side = '10000000-0000-0000-0000-000000000002';
const signal = new AbortController().signal;
const link = { exerciseId: exercise, parentConversationId: exercise, conversationId: side, createdAt: '2026-01-01T00:00:00Z' };
beforeEach(() => vi.resetAllMocks());
it('binds side metadata to its original exercise and main conversation', async () => {
  vi.mocked(request).mockResolvedValue(link); await expect(find('owner', exercise, exercise, signal)).resolves.toEqual(link);
  for (const bad of [{ ...link, exerciseId: side }, { ...link, parentConversationId: side }, { ...link, conversationId: exercise }]) {
    vi.mocked(request).mockResolvedValue(bad); await expect(find('owner', exercise, exercise, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
  vi.mocked(request).mockResolvedValue(null); await expect(find('owner', exercise, exercise, signal)).resolves.toBeNull();
});
it('posts only explicit side intent and rejects a receipt pointing back to main', async () => {
  const task = { id: side, conversationId: side, exerciseId: exercise, state: 'QUEUED', reason: null, stopRequested: false, createdAt: link.createdAt, completedAt: null };
  vi.mocked(post).mockResolvedValue(task); await start('owner', exercise, exercise, 'control', 'Side question', 'original', signal, side);
  expect(post).toHaveBeenCalledWith(`/api/exercises/${exercise}/copilot/conversations/${exercise}/btw/tasks`, { text: 'Side question', feeAcknowledged: true }, signal, 'original', 'control', 'owner');
  vi.mocked(post).mockResolvedValue({ ...task, conversationId: exercise }); await expect(start('owner', exercise, exercise, 'control', 'Side question', 'original', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
