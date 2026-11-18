import { ApiError, object, ownerHeaders, request } from '../api/request';

export interface CopilotTelemetryTotals {
  taskCount: number;
  rounds: number;
  tools: number;
  modelElapsedMs: number | null;
  toolElapsedMs: number | null;
  readWindows: number;
  readSlotsUsed: number | null;
  exploreChildren: number;
  microCompactedRounds: number;
  microSavedBytes: number | null;
  recoveredRevisionConflicts: number;
  steerInterruptions: number;
  chatInputTokens: number | null;
  chatOutputTokens: number | null;
  chatCacheReadTokens: number | null;
  chatCacheWriteTokens: number | null;
  readWindowUtilization: number | null;
}

export interface CopilotTelemetryTask {
  taskId: string;
  state: string;
  createdAt: string;
  rounds: number;
  tools: number;
  modelElapsedMs: number | null;
  toolElapsedMs: number | null;
  microSavedBytes: number | null;
}

export interface CopilotTelemetry {
  totals: CopilotTelemetryTotals;
  recentTasks: CopilotTelemetryTask[];
}

function invalid(): never {
  throw new ApiError(502, 'INVALID_RESPONSE');
}

function nonNegInt(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return invalid();
  return value;
}

function nullableNonNegInt(value: unknown): number | null {
  return value === null ? null : nonNegInt(value);
}

function nullableRatio(value: unknown): number | null {
  if (value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) return invalid();
  return value;
}

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value.length || value.length > max) return invalid();
  return value;
}

export function parseTotals(value: unknown): CopilotTelemetryTotals {
  if (!object(value)) return invalid();
  return {
    taskCount: nonNegInt(value.taskCount),
    rounds: nonNegInt(value.rounds),
    tools: nonNegInt(value.tools),
    modelElapsedMs: nullableNonNegInt(value.modelElapsedMs),
    toolElapsedMs: nullableNonNegInt(value.toolElapsedMs),
    readWindows: nonNegInt(value.readWindows),
    readSlotsUsed: nullableNonNegInt(value.readSlotsUsed),
    exploreChildren: nonNegInt(value.exploreChildren),
    microCompactedRounds: nonNegInt(value.microCompactedRounds),
    microSavedBytes: nullableNonNegInt(value.microSavedBytes),
    recoveredRevisionConflicts: nonNegInt(value.recoveredRevisionConflicts),
    steerInterruptions: nonNegInt(value.steerInterruptions),
    chatInputTokens: nullableNonNegInt(value.chatInputTokens),
    chatOutputTokens: nullableNonNegInt(value.chatOutputTokens),
    chatCacheReadTokens: nullableNonNegInt(value.chatCacheReadTokens),
    chatCacheWriteTokens: nullableNonNegInt(value.chatCacheWriteTokens),
    readWindowUtilization: nullableRatio(value.readWindowUtilization),
  };
}

function parseTask(value: unknown): CopilotTelemetryTask {
  if (!object(value)) return invalid();
  const createdAt = text(value.createdAt, 64);
  if (!Number.isFinite(Date.parse(createdAt))) return invalid();
  return {
    taskId: text(value.taskId, 64),
    state: text(value.state, 32),
    createdAt,
    rounds: nonNegInt(value.rounds),
    tools: nonNegInt(value.tools),
    modelElapsedMs: nullableNonNegInt(value.modelElapsedMs),
    toolElapsedMs: nullableNonNegInt(value.toolElapsedMs),
    microSavedBytes: nullableNonNegInt(value.microSavedBytes),
  };
}

export async function exerciseTelemetry(owner: string, exerciseId: string, signal: AbortSignal): Promise<CopilotTelemetry> {
  const data = await request(`/api/exercises/${encodeURIComponent(exerciseId)}/copilot/telemetry`, signal, {
    headers: ownerHeaders(owner),
  });
  if (!object(data) || !Array.isArray(data.recentTasks) || data.recentTasks.length > 20) return invalid();
  return {
    totals: parseTotals(data.totals),
    recentTasks: data.recentTasks.map(parseTask),
  };
}

export async function adminCopilotMetrics(owner: string, signal: AbortSignal): Promise<CopilotTelemetryTotals> {
  const data = await request('/api/admin/metrics/copilot', signal, {
    headers: ownerHeaders(owner),
  });
  return parseTotals(data);
}
