import { afterEach, expect, it, vi } from 'vitest';
import { checkUsage, parseUsage } from './usageApi';

const totals = { purpose: 'CHECK', calls: '2', completeCalls: '1', inputTokens: '1000', ordinaryInputTokens: '300', cacheReadTokens: '600', cacheWriteTokens: '100', outputTokens: '50', totalTokens: '1050', pricedCalls: '1' };
const costs = [{ purpose: 'CHECK', currency: 'USD', amount: '0.0000918', pricedCalls: '1' }];
afterEach(() => vi.unstubAllGlobals());

it('keeps large counts and money as decimal strings and rejects contradictory or unbounded summaries', () => {
  expect(parseUsage({ totals: [totals], costs }, ['CHECK']).costs[0]?.amount).toBe('0.0000918');
  for (const bad of [{ totals: [{ ...totals, calls: 2 }], costs }, { totals: [totals, totals], costs },
    { totals: [{ ...totals, completeCalls: '3' }], costs }, { totals: [{ ...totals, inputTokens: '-1' }], costs },
    { totals: [totals], costs: [{ ...costs[0], amount: 'NaN' }] }, { totals: [totals], costs: [] },
    { totals: [{ ...totals, purpose: 'PLATFORM' }], costs }]) {
    expect(() => parseUsage(bad, ['CHECK'])).toThrow();
  }
  expect(parseUsage({ totals: [{ ...totals, calls: '9007199254740993', inputTokens: null }], costs }, ['CHECK']).totals[0]?.inputTokens).toBeNull();
});

it('binds read-only check usage to its owner and requested group', async () => {
  const id = '10000000-0000-0000-0000-000000000001';
  const fetcher = vi.fn<typeof fetch>(async () => new Response(JSON.stringify({ code: 'OK', data: { checkId: id, totals: [totals], costs } })));
  vi.stubGlobal('fetch', fetcher);
  await expect(checkUsage('owner', id, new AbortController().signal)).resolves.toMatchObject({ checkId: id });
  expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ cache: 'no-store', headers: { 'X-Codecraft-Owner': 'owner' } });
  await expect(checkUsage('owner', '10000000-0000-0000-0000-000000000002', new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
