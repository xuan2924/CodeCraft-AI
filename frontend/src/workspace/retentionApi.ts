import { ApiError, object, ownerHeaders, post, remove, request } from '../api/request';

export interface HistoryEntry { exerciseId: string; endedAt: string; retainUntil: string; deletionState: string | null }
export interface Deletion { exerciseId: string; state: string; requestedAt: string; completedAt: string | null; nextAttemptAt: string; failureCode: string | null; interventionRequired: boolean }
const states = ['PENDING', 'RUNNING', 'FAILED', 'SUCCEEDED'];
function timestamp(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)); }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
export async function history(signal: AbortSignal, after?: string, owner?: string): Promise<HistoryEntry[]> {
  const value = await request(`/api/exercises${after ? `?after=${encodeURIComponent(after)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 50) return invalid();
  return value.map((item: unknown) => {
    if (!object(item) || typeof item.exerciseId !== 'string' || !timestamp(item.endedAt) || !timestamp(item.retainUntil)
      || item.deletionState !== null && !states.includes(String(item.deletionState))) return invalid();
    return { exerciseId: item.exerciseId, endedAt: item.endedAt, retainUntil: item.retainUntil, deletionState: item.deletionState === null ? null : String(item.deletionState) };
  });
}
function deletion(value: unknown, id: string): Deletion {
  if (!object(value) || value.exerciseId !== id || !states.includes(String(value.state)) || !timestamp(value.requestedAt)
    || !timestamp(value.nextAttemptAt) || value.completedAt !== null && !timestamp(value.completedAt)
    || value.failureCode !== null && typeof value.failureCode !== 'string' || typeof value.interventionRequired !== 'boolean') return invalid();
  return { exerciseId: id, state: String(value.state), requestedAt: value.requestedAt, completedAt: value.completedAt,
    nextAttemptAt: value.nextAttemptAt, failureCode: value.failureCode, interventionRequired: value.interventionRequired };
}
export async function deleteExercise(id: string, key: string, signal: AbortSignal): Promise<Deletion> {
  return deletion(await remove(`/api/exercises/${encodeURIComponent(id)}`, signal, key), id);
}
export async function progress(id: string, signal: AbortSignal): Promise<Deletion> {
  return deletion(await request(`/api/exercises/${encodeURIComponent(id)}/deletion`, signal), id);
}
export async function retry(id: string, key: string, signal: AbortSignal): Promise<Deletion> {
  return deletion(await post(`/api/exercises/${encodeURIComponent(id)}/deletion/retry`, {}, signal, key), id);
}
