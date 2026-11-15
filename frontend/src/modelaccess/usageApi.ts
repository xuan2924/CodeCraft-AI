import { ApiError, object, ownerHeaders, request } from '../api/request';

export type UsagePurpose = 'CHAT' | 'SUMMARY' | 'CHECK';
export interface UsageTotals {
  purpose: UsagePurpose; calls: string; completeCalls: string; inputTokens: string | null; ordinaryInputTokens: string | null;
  cacheReadTokens: string | null; cacheWriteTokens: string | null; outputTokens: string | null; totalTokens: string | null; pricedCalls: string;
}
export interface CostSubtotal { purpose: UsagePurpose; currency: string; amount: string; pricedCalls: string }
export interface UsageSummary { totals: UsageTotals[]; costs: CostSubtotal[] }
export interface CheckUsage extends UsageSummary { checkId: string }
export function invalidUsage(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
export function usageCount(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,39})$/.test(value)) return invalidUsage();
  return value;
}
function optionalCount(value: unknown) { return value === null ? null : usageCount(value); }
function purpose(value: unknown, allowed: readonly UsagePurpose[]): UsagePurpose {
  if (value !== 'CHAT' && value !== 'SUMMARY' && value !== 'CHECK' || !allowed.includes(value)) return invalidUsage();
  return value;
}
export function parseUsage(value: unknown, allowed: readonly UsagePurpose[]): UsageSummary {
  if (!object(value) || !Array.isArray(value.totals) || value.totals.length > 3 || !Array.isArray(value.costs) || value.costs.length > 24) return invalidUsage();
  const seen = new Set<UsagePurpose>();
  const totals = value.totals.map((item): UsageTotals => {
    if (!object(item)) return invalidUsage();
    const row = { purpose: purpose(item.purpose, allowed), calls: usageCount(item.calls), completeCalls: usageCount(item.completeCalls),
      inputTokens: optionalCount(item.inputTokens), ordinaryInputTokens: optionalCount(item.ordinaryInputTokens),
      cacheReadTokens: optionalCount(item.cacheReadTokens), cacheWriteTokens: optionalCount(item.cacheWriteTokens),
      outputTokens: optionalCount(item.outputTokens), totalTokens: optionalCount(item.totalTokens), pricedCalls: usageCount(item.pricedCalls) };
    if (seen.has(row.purpose) || BigInt(row.completeCalls) > BigInt(row.calls) || BigInt(row.pricedCalls) > BigInt(row.calls)) return invalidUsage();
    seen.add(row.purpose); return row;
  });
  const buckets = new Set<string>();
  const costs = value.costs.map((item): CostSubtotal => {
    if (!object(item) || typeof item.currency !== 'string' || !/^[A-Z]{3}$/.test(item.currency)
      || typeof item.amount !== 'string' || !/^(0|[1-9]\d{0,35})(\.\d{1,18})?$/.test(item.amount)) return invalidUsage();
    const row = { purpose: purpose(item.purpose, allowed), currency: item.currency, amount: item.amount, pricedCalls: usageCount(item.pricedCalls) };
    const key = `${row.purpose}/${row.currency}`;
    if (!seen.has(row.purpose) || buckets.has(key) || row.pricedCalls === '0') return invalidUsage();
    buckets.add(key); return row;
  });
  for (const row of totals) {
    if (costs.filter((item) => item.purpose === row.purpose).reduce((sum, item) => sum + BigInt(item.pricedCalls), 0n) !== BigInt(row.pricedCalls)) return invalidUsage();
  }
  return { totals, costs };
}
export async function checkUsage(owner: string, id: string, signal: AbortSignal): Promise<CheckUsage> {
  const value = await request(`/api/model-checks/${encodeURIComponent(id)}/usage`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.checkId !== id) return invalidUsage();
  return { checkId: id, ...parseUsage(value, ['CHECK']) };
}
