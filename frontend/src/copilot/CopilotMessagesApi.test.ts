import { beforeEach, expect, it, vi } from 'vitest';
import { request } from '../api/request';
import { messages } from './api';
vi.mock('../api/request', async (original) => ({ ...await original<typeof import('../api/request')>(), request: vi.fn() }));
const id = '10000000-0000-0000-0000-000000000001';
const entry = { id, taskId: id, sequence: '1', kind: 'ASSISTANT', text: 'answer', thinking: 'plan' };
const signal = new globalThis.AbortController().signal;
beforeEach(() => vi.resetAllMocks());
it('keeps display thinking distinct from answer text', async () => {
  vi.mocked(request).mockResolvedValue({ messages: [entry], nextCursor: '1' });
  expect((await messages('owner', id, '0', signal)).messages[0]).toMatchObject({ text: 'answer', thinking: 'plan' });
});
it('rejects malformed thinking and includes thinking in the page budget', async () => {
  for (const thinking of [123, 'x'.repeat(524288)]) {
    vi.mocked(request).mockResolvedValue({ messages: [{ ...entry, thinking }], nextCursor: '1' });
    await expect(messages('owner', id, '0', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
