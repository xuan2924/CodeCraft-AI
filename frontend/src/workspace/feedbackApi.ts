import { ApiError, object, ownerHeaders, post, request } from '../api/request';
export interface FeedbackInput { targetType: 'REPORT' | 'INTERVIEWER'; targetId: string; generation: number; description: string; evidenceIds: string[]; previewFingerprint?: string }
export interface FeedbackPreview { snapshot: string; fingerprint: string }
export interface Feedback { id: string; snapshot: string; state: 'PENDING' | 'HANDLED'; reply: string | null; createdAt: string; handledAt: string | null }
const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
function parse(value: unknown): Feedback {
  if (!object(value) || typeof value.id !== 'string' || typeof value.snapshot !== 'string' || !['PENDING', 'HANDLED'].includes(String(value.state))
    || !(value.reply === null || typeof value.reply === 'string') || typeof value.createdAt !== 'string' || !(value.handledAt === null || typeof value.handledAt === 'string')) return invalid();
  return { id: value.id, snapshot: value.snapshot, state: value.state === 'HANDLED' ? 'HANDLED' : 'PENDING', reply: value.reply, createdAt: value.createdAt, handledAt: value.handledAt };
}
const path = (exercise: string) => `/api/exercises/${encodeURIComponent(exercise)}/feedback`;
export async function preview(owner: string, exercise: string, input: FeedbackInput, signal: AbortSignal): Promise<FeedbackPreview> {
  const value = await post(`${path(exercise)}/preview`, input, signal, undefined, undefined, owner);
  if (!object(value) || typeof value.snapshot !== 'string' || typeof value.fingerprint !== 'string') return invalid();
  return { snapshot: value.snapshot, fingerprint: value.fingerprint };
}
export async function submit(owner: string, exercise: string, input: FeedbackInput, key: string, signal: AbortSignal): Promise<Feedback> {
  return parse(await post(path(exercise), input, signal, key, undefined, owner));
}
export async function list(owner: string, admin: boolean, after: string | null, signal: AbortSignal): Promise<Feedback[]> {
  const value = await request(`/api/${admin ? 'admin/' : ''}feedback${after ? `?after=${encodeURIComponent(after)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value)) return invalid();
  return value.map(parse);
}
export async function reply(owner: string, id: string, reply: string, key: string, signal: AbortSignal): Promise<Feedback> {
  return parse(await post(`/api/admin/feedback/${encodeURIComponent(id)}/reply`, { reply }, signal, key, undefined, owner));
}
