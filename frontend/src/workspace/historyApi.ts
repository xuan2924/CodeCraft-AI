import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { parseMutation, type Mutation } from './api';
import { managedPath, parseFileDifferences, type FileDifference } from './fileDifference';

export interface CodeChange {
  id: string; exerciseId: string; source: string; baseRevision: string; resultRevision: string;
  taskId: string | null; conversationId: string | null; originChangeId: string | null; retained: boolean; recordedAt: string;
}
export interface CodeHistory { exerciseId: string; changes: CodeChange[]; nextBefore: string | null }
export type HistoryMode = 'DIFF' | 'UNDO' | 'ROLLBACK';
export interface PreviewInput { mode: HistoryMode; expectedRevision: string; targetRevision: string | null }
export interface HistoryPreview extends PreviewInput {
  exerciseId: string; changeId: string; fingerprint: string; changes: FileDifference[]; conflicts: string[];
}
export interface RestoreInput {
  changeId: string; mode: 'UNDO' | 'ROLLBACK'; expectedRevision: string; targetRevision: string | null; previewFingerprint: string;
}
export type RestoreOutcome = Mutation | { notAccepted: true; reason: string };
const sources = new Set(['MANUAL', 'AI_PATCH', 'BASH', 'UNDO', 'ROLLBACK']);
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function id(value: unknown): value is string { return typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value); }
function counter(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n;
}
function base(exercise: string) { return `/api/exercises/${encodeURIComponent(exercise)}/history`; }
export async function history(owner: string, exercise: string, before: string | null, signal: AbortSignal): Promise<CodeHistory> {
  const value = await request(`${base(exercise)}${before ? `?before=${encodeURIComponent(before)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.exerciseId !== exercise || !Array.isArray(value.changes) || value.changes.length > 20
    || (value.nextBefore !== null && (!counter(value.nextBefore) || value.nextBefore === '0'))) return invalid();
  const changes = value.changes.map((entry: unknown): CodeChange => {
    if (!object(entry) || !id(entry.id) || entry.exerciseId !== exercise || typeof entry.source !== 'string' || !sources.has(entry.source)
      || !counter(entry.baseRevision) || !counter(entry.resultRevision) || BigInt(entry.resultRevision) !== BigInt(entry.baseRevision) + 1n
      || (entry.taskId !== null && !id(entry.taskId)) || (entry.conversationId !== null && !id(entry.conversationId))
      || (entry.originChangeId !== null && !id(entry.originChangeId)) || typeof entry.retained !== 'boolean'
      || typeof entry.recordedAt !== 'string' || entry.recordedAt.length > 64 || !Number.isFinite(Date.parse(entry.recordedAt))) return invalid();
    return { id: entry.id, exerciseId: exercise, source: entry.source, baseRevision: entry.baseRevision, resultRevision: entry.resultRevision,
      taskId: entry.taskId, conversationId: entry.conversationId, originChangeId: entry.originChangeId, retained: entry.retained, recordedAt: entry.recordedAt };
  });
  if (new Set(changes.map(change => change.id)).size !== changes.length
    || changes.some((change, index) => (before !== null && BigInt(change.resultRevision) >= BigInt(before))
      || (index > 0 && BigInt(change.resultRevision) >= BigInt(changes[index - 1]!.resultRevision)))
    || (value.nextBefore !== null && (changes.length !== 20 || value.nextBefore !== changes.at(-1)?.resultRevision))) return invalid();
  return { exerciseId: exercise, changes, nextBefore: value.nextBefore };
}
export async function preview(owner: string, exercise: string, change: CodeChange, input: PreviewInput, signal: AbortSignal): Promise<HistoryPreview> {
  const query = new URLSearchParams({ revision: input.expectedRevision, mode: input.mode });
  if (input.targetRevision !== null) query.set('targetRevision', input.targetRevision);
  const value = await request(`${base(exercise)}/${encodeURIComponent(change.id)}/preview?${query}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.exerciseId !== exercise || value.changeId !== change.id || value.mode !== input.mode
    || value.expectedRevision !== input.expectedRevision || value.targetRevision !== input.targetRevision
    || typeof value.fingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(value.fingerprint) || !Array.isArray(value.conflicts)
    || value.conflicts.length > 400 || (input.mode !== 'UNDO' && value.conflicts.length > 0)) return invalid();
  const conflicts = value.conflicts.map((path: unknown) => {
    if (typeof path !== 'string' || !managedPath(path)) return invalid();
    return path;
  });
  if (new Set(conflicts).size !== conflicts.length) return invalid();
  return { exerciseId: exercise, changeId: change.id, ...input, fingerprint: value.fingerprint,
    changes: parseFileDifferences(value.changes, true), conflicts };
}
function outcome(value: unknown, exercise: string, expectedRevision: string, operationId?: string): Mutation {
  const result = parseMutation(value);
  if (result.exerciseId !== exercise || !id(result.operationId) || (operationId !== undefined && result.operationId !== operationId)
    || (result.revision !== null && !counter(result.revision))) return invalid();
  if (result.state === 'SUCCEEDED') {
    if (result.changed === null || result.revision === null || result.failureCode !== null
      || BigInt(result.revision) !== BigInt(expectedRevision) + (result.changed ? 1n : 0n)) return invalid();
  } else if (result.state === 'REJECTED') {
    if (!result.failureCode || result.failureCode.length > 128 || result.revision !== null || result.changed !== null) return invalid();
  } else if (result.revision !== null || result.changed !== null || result.failureCode !== null) return invalid();
  return result;
}
export async function restore(owner: string, exercise: string, input: RestoreInput, key: string, credential: string, signal: AbortSignal): Promise<RestoreOutcome> {
  const value = await post(`${base(exercise)}/restore`, input, signal, key, credential, owner);
  if (!object(value) || value.exerciseId !== exercise || value.requestKey !== key || !object(value.intent)
    || value.intent.changeId !== input.changeId || value.intent.mode !== input.mode || value.intent.expectedRevision !== input.expectedRevision
    || value.intent.targetRevision !== input.targetRevision || value.intent.previewFingerprint !== input.previewFingerprint) return invalid();
  if (value.notAcceptedReason !== null) {
    if (value.operation !== null || typeof value.notAcceptedReason !== 'string'
      || !['CODE_HISTORY_UNAVAILABLE', 'REVISION_CONFLICT'].includes(value.notAcceptedReason)) return invalid();
    return { notAccepted: true, reason: value.notAcceptedReason };
  }
  return outcome(value.operation, exercise, input.expectedRevision);
}
export async function operation(owner: string, exercise: string, operationId: string, expectedRevision: string, signal: AbortSignal): Promise<Mutation> {
  return outcome(await request(`/api/operations/${encodeURIComponent(operationId)}`, signal, { headers: ownerHeaders(owner) }), exercise, expectedRevision, operationId);
}
/** A delayed pending observation cannot erase a confirmed immutable write receipt. */
export function latest(current: Mutation | undefined, incoming: Mutation): Mutation {
  if (current?.operationId === incoming.operationId && ['SUCCEEDED', 'REJECTED'].includes(current.state)) return current;
  return incoming;
}
