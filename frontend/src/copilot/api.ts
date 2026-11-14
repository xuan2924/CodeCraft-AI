import { ApiError, events, object, ownerHeaders, post, request } from '../api/request';

const states = ['QUEUED', 'RUNNING', 'PAUSED', 'STOPPING', 'STOPPED', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'] as const;
export interface Task { id: string; exerciseId: string; state: typeof states[number]; reason: string | null; stopRequested: boolean; createdAt: string; completedAt: string | null }
export interface Snapshot { task: Task; lastSequence: string; textHistory: boolean; partial: { turnId: string | null; sequence: string; text: string; truncated: boolean } }
export interface Message { sequence: string; id: string; taskId: string; kind: 'USER' | 'ASSISTANT' | 'TOOL'; text: string }
export interface Messages { messages: Message[]; nextCursor: string | null }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) return invalid(); return value; }
function sequence(value: unknown): string { if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n) return invalid(); return value; }
function date(value: unknown): string { if (typeof value !== 'string' || value.length > 64 || !Number.isFinite(Date.parse(value))) return invalid(); return value; }
function task(value: unknown, exercise: string): Task {
  if (!object(value)) return invalid();
  const state = states.find((item) => item === value.state);
  if (!state || value.exerciseId !== exercise || typeof value.stopRequested !== 'boolean'
    || value.reason !== null && (typeof value.reason !== 'string' || value.reason.length > 128)) return invalid();
  return { id: uuid(value.id), exerciseId: uuid(value.exerciseId), state, reason: value.reason, stopRequested: value.stopRequested,
    createdAt: date(value.createdAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
function snapshot(value: unknown, exercise: string): Snapshot {
  if (!object(value) || typeof value.textHistory !== 'boolean' || !object(value.partial)) return invalid();
  const partial = value.partial;
  if (typeof partial.text !== 'string' || partial.text.length > 8192 || typeof partial.truncated !== 'boolean') return invalid();
  return { task: task(value.task, exercise), lastSequence: sequence(value.lastSequence), textHistory: value.textHistory,
    partial: { turnId: partial.turnId === null ? null : uuid(partial.turnId), sequence: sequence(partial.sequence), text: partial.text, truncated: partial.truncated } };
}
function path(id: string) { return encodeURIComponent(uuid(id)); }
export function active(task: Task | null | undefined) { return !!task && ['QUEUED', 'RUNNING', 'PAUSED', 'STOPPING'].includes(task.state); }
export async function latest(owner: string, exercise: string, signal: AbortSignal): Promise<Snapshot | null> {
  const value = await request(`/api/exercises/${path(exercise)}/copilot/tasks/latest`, signal, { headers: ownerHeaders(owner) });
  return value === null ? null : snapshot(value, exercise);
}
export async function start(owner: string, exercise: string, credential: string, text: string, key: string, signal: AbortSignal): Promise<Task> {
  return task(await post(`/api/exercises/${path(exercise)}/copilot/tasks`, { text, feeAcknowledged: true }, signal, key, credential, owner), exercise);
}
export async function stop(owner: string, exercise: string, id: string, key: string, signal: AbortSignal): Promise<Task> {
  const result = task(await post(`/api/copilot-tasks/${path(id)}/stop`, {}, signal, key, undefined, owner), exercise);
  if (result.id !== id) return invalid(); return result;
}
export async function messages(owner: string, exercise: string, after: string, signal: AbortSignal): Promise<Messages> {
  if (after !== '-1') sequence(after);
  const value = await request(`/api/exercises/${path(exercise)}/copilot/messages?after=${encodeURIComponent(after)}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.messages) || value.messages.length > 100) return invalid();
  let previous = BigInt(after); let characters = 0;
  const items = value.messages.map((entry: unknown): Message => {
    if (!object(entry) || !['USER', 'ASSISTANT', 'TOOL'].includes(String(entry.kind)) || typeof entry.text !== 'string' || entry.text.length > 512 * 1024) return invalid();
    const seq = sequence(entry.sequence);
    if (BigInt(seq) <= previous) return invalid(); previous = BigInt(seq);
    characters += entry.text.length; if (characters > 524288) return invalid();
    const kind = entry.kind === 'USER' ? 'USER' : entry.kind === 'ASSISTANT' ? 'ASSISTANT' : 'TOOL';
    return { sequence: seq, id: uuid(entry.id), taskId: uuid(entry.taskId), kind, text: entry.text };
  });
  const nextCursor = value.nextCursor === null ? null : sequence(value.nextCursor);
  if (nextCursor !== null && nextCursor !== items.at(-1)?.sequence) return invalid();
  return { messages: items, nextCursor };
}
export function watchTask(exercise: string, id: string, receive: (snapshot: Snapshot) => void, failed: () => void, signal: AbortSignal) {
  return events(`/api/exercises/${path(exercise)}/copilot/tasks/${path(id)}/events`, ['copilot_snapshot'], (_type, data, cursor) => {
    try {
      if (data.length > 65536 || cursor.length > 512) return failed();
      const value = snapshot(JSON.parse(data), exercise);
      if (value.task.id !== id) return failed();
      receive(value);
    } catch { failed(); }
  }, failed, signal);
}
