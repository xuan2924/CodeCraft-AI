import { beforeEach, expect, it, vi } from 'vitest';
import { post, request } from '../api/request';
import { conversations, latest, messages, newConversation, start } from './api';
vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), request: vi.fn(), post: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const conversation = '10000000-0000-0000-0000-000000000002';
const signal = new AbortController().signal;
const initial = { id: exercise, exerciseId: exercise, initial: true, createdAt: '2026-01-01T00:00:00Z' };
const task = { id: conversation, exerciseId: exercise, conversationId: conversation, state: 'QUEUED', reason: null, stopRequested: false, createdAt: initial.createdAt, completedAt: null };
beforeEach(() => vi.resetAllMocks());
it('validates list ownership, bounded identities and the unique initial conversation', async () => {
  vi.mocked(request).mockResolvedValue([initial]);
  await expect(conversations('owner', exercise, signal)).resolves.toEqual([initial]);
  for (const entries of [[], [initial, initial], [{ ...initial, exerciseId: conversation }], [{ ...initial, id: conversation }], Array(101).fill(initial)]) {
    vi.mocked(request).mockResolvedValue(entries);
    await expect(conversations('owner', exercise, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
it('binds creation and starts to the intended exercise and conversation', async () => {
  vi.mocked(post).mockResolvedValue({ ...initial, id: conversation, initial: false });
  await newConversation('owner', exercise, 'control', 'original', signal);
  expect(post).toHaveBeenLastCalledWith(`/api/exercises/${exercise}/copilot/conversations`, {}, signal, 'original', 'control', 'owner');
  vi.mocked(post).mockResolvedValue(task);
  await start('owner', exercise, 'control', 'prompt', 'task-key', signal, [], conversation);
  expect(post).toHaveBeenLastCalledWith(`/api/exercises/${exercise}/copilot/tasks`, { text: 'prompt', feeAcknowledged: true, conversationId: conversation }, signal, 'task-key', 'control', 'owner');
});
it('passes explicit read scope and rejects a task snapshot from a different conversation', async () => {
  vi.mocked(request).mockResolvedValue({ task, lastSequence: '1', textHistory: false, partial: { turnId: null, sequence: '0', text: '', truncated: false } });
  await expect(latest('owner', exercise, signal, conversation)).resolves.toMatchObject({ task: { conversationId: conversation } });
  await expect(latest('owner', exercise, signal, exercise)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  vi.mocked(request).mockResolvedValue({ messages: [], nextCursor: null });
  await messages('owner', exercise, '-1', signal, conversation);
  expect(request).toHaveBeenLastCalledWith(`/api/exercises/${exercise}/copilot/messages?after=-1&conversation=${conversation}`, signal, { headers: { 'X-Codecraft-Owner': 'owner' } });
});
