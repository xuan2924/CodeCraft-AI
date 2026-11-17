import { ApiError, object, ownerHeaders, post, request } from '../api/request';

export type InterviewerMode = 'CLARIFY' | 'HINT' | 'DEFENSE';
export type InterviewerStage = 'BASE' | 'CHALLENGE';
export interface AskOptions { mode?: InterviewerMode; topic?: string }
export interface InterviewerTask {
  id: string; exerciseId: string; question: string; answer: string | null; state: string;
  reason: string | null; platformVersionId: string; phase: string;
  stage?: InterviewerStage; mode?: InterviewerMode; topic?: string; hintLevel?: number | null;
  revision: string | null; createdAt: string; completedAt: string | null;
}
function parse(value: unknown, exercise: string): InterviewerTask {
  if (!object(value) || typeof value.id !== 'string' || value.exerciseId !== exercise || typeof value.question !== 'string'
    || (value.answer !== null && typeof value.answer !== 'string') || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(String(value.state))
    || (value.reason !== null && typeof value.reason !== 'string') || typeof value.platformVersionId !== 'string'
    || !['READY', 'ACTIVE'].includes(String(value.phase)) || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || (value.stage !== undefined && value.stage !== null && !['BASE', 'CHALLENGE'].includes(String(value.stage)))
    || (value.mode !== undefined && value.mode !== null && !['CLARIFY', 'HINT', 'DEFENSE'].includes(String(value.mode)))
    || (value.topic !== undefined && value.topic !== null && (typeof value.topic !== 'string' || value.topic.length > 64))
    || (value.hintLevel !== undefined && value.hintLevel !== null && (typeof value.hintLevel !== 'number' || !Number.isInteger(value.hintLevel) || value.hintLevel < 1 || value.hintLevel > 3))
    || typeof value.createdAt !== 'string' || !Number.isFinite(Date.parse(value.createdAt))
    || (value.completedAt !== null && (typeof value.completedAt !== 'string' || !Number.isFinite(Date.parse(value.completedAt))))) throw new ApiError(502, 'INVALID_RESPONSE');
  return {
    id: value.id, exerciseId: exercise, question: value.question, answer: value.answer, state: String(value.state), reason: value.reason,
    platformVersionId: value.platformVersionId, phase: String(value.phase),
    ...(value.stage != null ? { stage: String(value.stage) as InterviewerStage } : {}),
    ...(value.mode != null ? { mode: String(value.mode) as InterviewerMode } : {}),
    ...(typeof value.topic === 'string' ? { topic: value.topic } : {}),
    ...(value.hintLevel !== undefined ? { hintLevel: value.hintLevel } : {}),
    revision: value.revision, createdAt: value.createdAt, completedAt: value.completedAt,
  };
}
const path = (exercise: string) => `/api/exercises/${encodeURIComponent(exercise)}/interviewer/tasks`;
export async function history(owner: string, exercise: string, before: string, signal: AbortSignal): Promise<InterviewerTask[]> {
  const value = await request(path(exercise) + (before ? `?before=${encodeURIComponent(before)}` : ''), signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 20) throw new ApiError(502, 'INVALID_RESPONSE');
  return value.map((item: unknown) => parse(item, exercise));
}
export async function ask(owner: string, exercise: string, text: string, key: string, credential: string, signal: AbortSignal, options?: AskOptions): Promise<InterviewerTask> {
  const body: { text: string; mode?: InterviewerMode; topic?: string } = { text };
  if (options?.mode) body.mode = options.mode;
  if (options?.topic?.trim()) body.topic = options.topic.trim();
  return parse(await post(path(exercise), body, signal, key, credential, owner), exercise);
}
