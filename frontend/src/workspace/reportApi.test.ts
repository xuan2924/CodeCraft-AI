import { afterEach, expect, it, vi } from 'vitest';
import { parseReport, pdf, retry } from './reportApi';
const value = { exerciseId: 'exercise', reportId: 'report', generation: 1, state: 'FAILED', finalRevision: '0', verification: 'UNAVAILABLE', finalTestId: null, reviewSkipped: true, platformVersionId: 'version', attempts: 3, reason: 'INVALID_RESPONSE', evaluation: null, evidence: [] };
afterEach(() => vi.unstubAllGlobals());
it('keeps revision zero and rejects invalid boundaries', () => {
  expect(parseReport(value).finalRevision).toBe('0');
  for (const invalid of [{ ...value, generation: 1.1 }, { ...value, state: 'invented' }, { ...value, evidence: [{}] }]) expect(() => parseReport(invalid)).toThrow('INVALID_RESPONSE');
});
it('sends the backend expectedGeneration retry contract with owner, CSRF and original key', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: value })));
  vi.stubGlobal('fetch', fetcher);
  await retry('owner', 'exercise', 1, 'retry-key', new AbortController().signal);
  const options = fetcher.mock.calls[1]?.[1];
  expect(JSON.parse(options.body)).toEqual({ expectedGeneration: 1 });
  expect(options.headers).toMatchObject({ 'X-Codecraft-Owner': 'owner', 'X-CSRF-TOKEN': 'csrf', 'Idempotency-Key': 'retry-key' });
});
it('private PDF transport rejects an authorization error or wrong content type', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND' }), { status: 404 }))
    .mockResolvedValueOnce(new Response('not a PDF', { headers: { 'Content-Type': 'text/html' } })));
  await expect(pdf('owner', 'exercise', 1, new AbortController().signal)).rejects.toThrow('RESOURCE_NOT_FOUND');
  await expect(pdf('owner', 'exercise', 1, new AbortController().signal)).rejects.toThrow('INVALID_RESPONSE');
});
