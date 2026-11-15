import { object, ownerHeaders, request } from '../api/request';
import { invalidUsage, parseUsage, usageCount, type UsageSummary } from '../modelaccess/usageApi';
export interface UsageContext {
  state: 'UNKNOWN' | 'REPORTED' | 'WAITING_UPDATE'; turnId: string | null; modelId: string | null;
  contextWindow: number | null; windowSource: 'USER_CONFIGURATION' | null; inputTokens: string | null; dispatchedAt: string | null;
}
export interface CopilotUsage extends UsageSummary { exerciseId: string; context: UsageContext }
function optionalText(value: unknown, max: number): string | null {
  if (value === null) return null;
  if (typeof value !== 'string' || !value.length || value.length > max) return invalidUsage();
  return value;
}
export async function usage(owner: string, exerciseId: string, signal: AbortSignal): Promise<CopilotUsage> {
  const value = await request(`/api/exercises/${encodeURIComponent(exerciseId)}/copilot/usage`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.exerciseId !== exerciseId || !object(value.context)) return invalidUsage();
  const item = value.context;
  if (item.state !== 'UNKNOWN' && item.state !== 'REPORTED' && item.state !== 'WAITING_UPDATE') return invalidUsage();
  if (item.contextWindow !== null && (typeof item.contextWindow !== 'number' || !Number.isSafeInteger(item.contextWindow) || item.contextWindow <= 0)) return invalidUsage();
  if (item.windowSource !== null && item.windowSource !== 'USER_CONFIGURATION') return invalidUsage();
  const inputTokens = item.inputTokens === null ? null : usageCount(item.inputTokens);
  if ((item.state === 'REPORTED') !== (inputTokens !== null)) return invalidUsage();
  const dispatchedAt = optionalText(item.dispatchedAt, 64);
  if (dispatchedAt !== null && !Number.isFinite(Date.parse(dispatchedAt))) return invalidUsage();
  const context: UsageContext = { state: item.state, turnId: optionalText(item.turnId, 64), modelId: optionalText(item.modelId, 256),
    contextWindow: item.contextWindow, windowSource: item.windowSource, inputTokens, dispatchedAt };
  return { exerciseId, context, ...parseUsage(value, ['CHAT', 'SUMMARY']) };
}
