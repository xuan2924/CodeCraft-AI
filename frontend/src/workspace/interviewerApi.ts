import { ApiError, object, ownerHeaders, post, request } from '../api/request';

export interface InterviewerTask {
  id: string; exerciseId: string; question: string; answer: string | null; state: string;
  reason: string | null; platformVersionId: string; phase: string; revision: string | null;
  createdAt: string; completedAt: string | null;
}
function parse(value: unknown, exercise: string): InterviewerTask {
  if (!object(value) || typeof value.id !== 'string' || value.exerciseId !== exercise || typeof value.question !== 'string'
    || (value.answer !== null && typeof value.answer !== 'string') || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(String(value.state))
    || (value.reason !== null && typeof value.reason !== 'string') || typeof value.platformVersionId !== 'string'
    || !['READY', 'ACTIVE'].includes(String(value.phase)) || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || typeof value.createdAt !== 'string' || !Number.isFinite(Date.parse(value.createdAt))
    || (value.completedAt !== null && (typeof value.completedAt !== 'string' || !Number.isFinite(Date.parse(value.completedAt))))) throw new ApiError(502, 'INVALID_RESPONSE');
  return { id: value.id, exerciseId: exercise, question: value.question, answer: value.answer, state: String(value.state), reason: value.reason,
    platformVersionId: value.platformVersionId, phase: String(value.phase), revision: value.revision, createdAt: value.createdAt, completedAt: value.completedAt };
}
const path = (exercise: string) => `/api/exercises/${encodeURIComponent(exercise)}/interviewer/tasks`;
export async function history(owner: string, exercise: string, before: string, signal: AbortSignal): Promise<InterviewerTask[]> {
  const value = await request(path(exercise) + (before ? `?before=${encodeURIComponent(before)}` : ''), signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 20) throw new ApiError(502, 'INVALID_RESPONSE');
  return value.map((item: unknown) => parse(item, exercise));
}
export async function ask(owner: string, exercise: string, text: string, key: string, credential: string, signal: AbortSignal): Promise<InterviewerTask> {
  return parse(await post(path(exercise), { text }, signal, key, credential, owner), exercise);
}
