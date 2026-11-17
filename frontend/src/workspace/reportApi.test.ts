import { afterEach, expect, it, vi } from 'vitest';
import { parseReport, pdf, retry } from './reportApi';
const value = { exerciseId: 'exercise', reportId: 'report', generation: 1, state: 'FAILED', finalRevision: '0', verification: 'UNAVAILABLE', finalTestId: null, reviewSkipped: true, platformVersionId: 'version', attempts: 3, reason: 'INVALID_RESPONSE', evaluation: null, evidence: [] };
afterEach(() => vi.unstubAllGlobals());
it('keeps revision zero and rejects invalid boundaries', () => {
  expect(parseReport(value).finalRevision).toBe('0');
  for (const invalid of [{ ...value, generation: 1.1 }, { ...value, state: 'invented' }, { ...value, evidence: [{}] }]) expect(() => parseReport(invalid)).toThrow('INVALID_RESPONSE');
});
it('parses optional timeline and contextTrace and rejects malformed trace or timeline entries', () => {
  const withTrace = {
    ...value,
    timeline: [
      { timestamp: '2026-11-17T03:00:00Z', kind: 'WORKSPACE_CHANGED', title: '应用修改 (r0 → r1)', detail: 'COPILOT_PATCH · 2 files', revision: '1', sourceId: 'chg-1' },
    ],
    contextTrace: {
      referenceCount: 2,
      skillLoadCount: 1,
      steerCount: 0,
      btwTaskCount: 1,
      interviewerClarifyCount: 2,
      interviewerDefenseCount: 1,
      hintsUsed: [{ topic: 'discount-rounding', maxLevel: 2 }],
    },
  };
  const parsed = parseReport(withTrace);
  expect(parsed.timeline).toHaveLength(1);
  expect(parsed.timeline![0]).toMatchObject({ kind: 'WORKSPACE_CHANGED', revision: '1', sourceId: 'chg-1' });
  expect(parsed.contextTrace).toMatchObject({ referenceCount: 2, steerCount: 0, hintsUsed: [{ topic: 'discount-rounding', maxLevel: 2 }] });

  expect(() => parseReport({ ...value, timeline: [{ timestamp: 'not-a-date', kind: 'X', title: 't' }] })).toThrow('INVALID_RESPONSE');
  expect(() => parseReport({ ...value, contextTrace: { ...withTrace.contextTrace, referenceCount: -1 } })).toThrow('INVALID_RESPONSE');
  expect(() => parseReport({ ...value, contextTrace: { ...withTrace.contextTrace, hintsUsed: [{ topic: 't', maxLevel: 4 }] } })).toThrow('INVALID_RESPONSE');
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
