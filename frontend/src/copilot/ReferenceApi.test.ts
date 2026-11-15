import { beforeEach, expect, it, vi } from 'vitest';
import { post, request } from '../api/request';
import { preview, selection } from './referenceApi';
import { start } from './api';
vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), request: vi.fn(), post: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const signal = new AbortController().signal;
const page = { kind: 'FILE', source: 'src/Main.java', version: 'workspace:1', offset: 0, limit: 2048, fingerprint: 'a'.repeat(64), text: '中文', nextOffset: null, retainedBytes: 6, sourceIncomplete: false, droppedBytes: '0' };
beforeEach(() => vi.resetAllMocks());
it('checks exact source/version/position and UTF-8 continuation without mistaking source loss for pagination', async () => {
  vi.mocked(request).mockResolvedValue(page); await expect(preview('owner', exercise, exercise, 'FILE', page.source, 0, 2048, page.version, signal)).resolves.toEqual(page);
  for (const changed of [{ ...page, source: 'wrong' }, { ...page, version: 'workspace:2' }, { ...page, nextOffset: 2 }, { ...page, retainedBytes: 0 }, { ...page, droppedBytes: '-1' }]) {
    vi.mocked(request).mockResolvedValue(changed); await expect(preview('owner', exercise, exercise, 'FILE', page.source, 0, 2048, page.version, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
it('submits reference identities rather than client-supplied source text', async () => {
  vi.mocked(request).mockResolvedValue(page); const verified = await preview('owner', exercise, exercise, 'FILE', page.source, 0, 2048, null, signal);
  vi.mocked(post).mockResolvedValue({ id: exercise, conversationId: exercise, exerciseId: exercise, state: 'QUEUED', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: null });
  await start('owner', exercise, 'control', 'question', 'original', signal, [], exercise, [selection(verified)]);
  expect(post).toHaveBeenLastCalledWith(`/api/exercises/${exercise}/copilot/tasks`, { text: 'question', feeAcknowledged: true, conversationId: exercise, references: [selection(verified)] }, signal, 'original', 'control', 'owner');
  expect(selection(verified)).not.toHaveProperty('text');
});
