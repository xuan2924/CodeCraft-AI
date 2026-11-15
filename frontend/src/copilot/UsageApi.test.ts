import { afterEach, expect, it, vi } from 'vitest';
import { usage } from './usageApi';
afterEach(() => vi.unstubAllGlobals());
it('validates reported context separately from totals and rejects stale post-compaction occupancy', async () => {
  const context = { state: 'WAITING_UPDATE', turnId: 'turn', modelId: 'model', contextWindow: 128000, windowSource: 'USER_CONFIGURATION', inputTokens: null, dispatchedAt: '2026-11-15T00:00:00Z' };
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'OK', data: { exerciseId: 'exercise', context, totals: [], costs: [] } })));
  vi.stubGlobal('fetch', fetch);
  const result = await usage('owner', 'exercise', new AbortController().signal);
  expect(result.context.inputTokens).toBeNull();
  expect(fetch.mock.calls[0]?.[0]).toBe('/api/exercises/exercise/copilot/usage');
  fetch.mockResolvedValue(new Response(JSON.stringify({ code: 'OK', data: { exerciseId: 'exercise', context: { ...context, inputTokens: '100' }, totals: [], costs: [] } })));
  await expect(usage('owner', 'exercise', new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
