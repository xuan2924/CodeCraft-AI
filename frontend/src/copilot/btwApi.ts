import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { parseTask, type Task } from './api';
export interface Btw { exerciseId: string; parentConversationId: string; conversationId: string; createdAt: string }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) return invalid(); return value; }
function base(exercise: string, parent: string) { return `/api/exercises/${uuid(exercise)}/copilot/conversations/${uuid(parent)}/btw`; }
export async function find(owner: string, exercise: string, parent: string, signal: AbortSignal): Promise<Btw | null> {
  const value = await request(base(exercise, parent), signal, { headers: ownerHeaders(owner) });
  if (value === null) return null;
  if (!object(value) || value.exerciseId !== exercise || value.parentConversationId !== parent || value.conversationId === exercise || value.conversationId === parent
    || typeof value.createdAt !== 'string' || value.createdAt.length > 64 || !Number.isFinite(Date.parse(value.createdAt))) return invalid();
  return { exerciseId: exercise, parentConversationId: parent, conversationId: uuid(value.conversationId), createdAt: value.createdAt };
}
export async function start(owner: string, exercise: string, parent: string, credential: string, text: string, key: string, signal: AbortSignal, expectedConversation?: string): Promise<Task> {
  const result = parseTask(await post(`${base(exercise, parent)}/tasks`, { text, feeAcknowledged: true }, signal, key, credential, owner), exercise, expectedConversation);
  if (!result.conversationId || result.conversationId === exercise || result.conversationId === parent) return invalid();
  return result;
}
