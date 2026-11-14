import { afterEach, expect, it, vi } from 'vitest';
import { remove } from './request';

afterEach(() => vi.unstubAllGlobals());
it('sends the original deletion version and idempotency key through the shared CSRF boundary', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { deleted: true } })));
  vi.stubGlobal('fetch', fetch);
  await remove('/api/model-connections/fixture', new AbortController().signal, 'original-key', { expectedVersion: '3' });
  expect(fetch.mock.calls[1]?.[1]).toMatchObject({ method: 'DELETE', body: '{"expectedVersion":"3"}',
    headers: { 'X-CSRF-TOKEN': 'csrf', 'Idempotency-Key': 'original-key' }, credentials: 'same-origin', cache: 'no-store' });
});
