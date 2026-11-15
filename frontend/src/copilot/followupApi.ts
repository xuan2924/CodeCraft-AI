import { ApiError, object, ownerHeaders, post, request } from '../api/request';
const states = ['WAITING', 'PAUSED', 'DISPATCHED', 'CANCELLED'] as const;
export interface Followup { id: string; sequence: string; exerciseId: string; conversationId: string; text: string; state: typeof states[number]; reason: string | null; taskId: string | null; createdAt: string; completedAt: string | null }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) return invalid(); return value; }
function date(value: unknown): string { if (typeof value !== 'string' || value.length > 64 || !Number.isFinite(Date.parse(value))) return invalid(); return value; }
function decode(value: unknown, exercise: string, conversation: string): Followup {
  if (!object(value)) return invalid();
  const state = states.find(item => value.state === item);
  if (!state || value.exerciseId !== exercise || value.conversationId !== conversation || typeof value.text !== 'string' || !value.text.trim()
    || new TextEncoder().encode(value.text).length > 4096 || value.reason !== null && (typeof value.reason !== 'string' || value.reason.length > 128)
    || typeof value.sequence !== 'string' || !/^[1-9]\d{0,18}$/.test(value.sequence) || BigInt(value.sequence) > 9223372036854775807n
    || (state === 'DISPATCHED') !== (value.taskId !== null) || (state === 'DISPATCHED' || state === 'CANCELLED') !== (value.completedAt !== null)) return invalid();
  return { id: uuid(value.id), sequence: value.sequence, exerciseId: exercise, conversationId: conversation, text: value.text, state, reason: value.reason,
    taskId: value.taskId === null ? null : uuid(value.taskId), createdAt: date(value.createdAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
function base(exercise: string, conversation: string) { return `/api/exercises/${uuid(exercise)}/copilot/conversations/${uuid(conversation)}/followups`; }
export async function recent(owner: string, exercise: string, conversation: string, signal: AbortSignal): Promise<Followup[]> {
  const value = await request(base(exercise, conversation), signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 132) return invalid();
  const items = value.map((item: unknown) => decode(item, exercise, conversation));
  if (new Set(items.map(item => item.id)).size !== items.length || items.some((item, index) => index > 0 && BigInt(items[index - 1]!.sequence) >= BigInt(item.sequence))) return invalid();
  return items;
}
export async function enqueue(owner: string, exercise: string, conversation: string, credential: string, text: string, key: string, signal: AbortSignal): Promise<Followup> {
  const value = await post(base(exercise, conversation), { text, feeAcknowledged: true }, signal, key, credential, owner);
  const result = decode(value, exercise, conversation);
  if (result.text !== text) return invalid();
  return result;
}
export async function cancel(owner: string, exercise: string, conversation: string, id: string, key: string, signal: AbortSignal): Promise<Followup> {
  const result = decode(await post(`${base(exercise, conversation)}/${uuid(id)}/cancel`, {}, signal, key, undefined, owner), exercise, conversation);
  if (result.id !== id || result.state !== 'CANCELLED') return invalid();
  return result;
}
export async function resume(owner: string, exercise: string, conversation: string, credential: string, key: string, signal: AbortSignal): Promise<{ conversationId: string; requestKey: string }> {
  const result = await post(`${base(exercise, conversation)}/resume`, { feeAcknowledged: true }, signal, key, credential, owner);
  if (!object(result) || result.conversationId !== conversation || result.requestKey !== key) return invalid();
  return { conversationId: conversation, requestKey: key };
}
