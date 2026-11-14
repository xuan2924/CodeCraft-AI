import { ApiError, events, object, ownerHeaders, post, request } from '../api/request';

const states = ['QUEUED', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'STOPPING', 'STOPPED', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'] as const;
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
export function active(task: Task | null | undefined) { return !!task && ['QUEUED', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'STOPPING'].includes(task.state); }
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
const testResults = ['PASSED', 'FAILED', 'TIMED_OUT', 'STOPPED', 'ENVIRONMENT_ERROR', 'INTERRUPTED'] as const;
export interface TestRun {
  toolId: string; taskId: string; testTaskId: string; revision: string; target: 'base'; state: 'WAITING' | 'RESOLVED';
  resultState: typeof testResults[number] | null; createdAt: string; completedAt: string | null;
}
export async function tests(owner: string, taskId: string, signal: AbortSignal): Promise<TestRun[]> {
  const value = await request(`/api/copilot-tasks/${path(taskId)}/tests`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 20) return invalid();
  const ids = new Set<string>();
  return value.map((entry: unknown): TestRun => {
    if (!object(entry) || entry.taskId !== taskId || entry.target !== 'base' || entry.state !== 'WAITING' && entry.state !== 'RESOLVED') return invalid();
    const resultState = testResults.find((state) => state === entry.resultState) ?? null;
    if (entry.state === 'WAITING' ? entry.resultState !== null || entry.completedAt !== null : !resultState || entry.completedAt === null) return invalid();
    const testTaskId = uuid(entry.testTaskId);
    if (ids.has(testTaskId)) return invalid(); ids.add(testTaskId);
    return { toolId: uuid(entry.toolId), taskId: uuid(entry.taskId), testTaskId, revision: sequence(entry.revision), target: 'base', state: entry.state,
      resultState, createdAt: date(entry.createdAt), completedAt: entry.completedAt === null ? null : date(entry.completedAt) };
  });
}
const proposalStates = ['PENDING', 'APPLYING', 'APPLIED', 'REJECTED', 'INVALIDATED', 'FAILED', 'CANCELLED'] as const;
export interface Proposal {
  id: string; sequence: string; taskId: string; state: typeof proposalStates[number]; mode: 'ASK' | 'AUTO'; baseRevision: string;
  fileCount: number; resultRevision: string | null; changed: boolean | null; decisionType: 'APPROVE' | 'REJECT' | null;
  decisionReason: string | null; failureCode: string | null; createdAt: string; decidedAt: string | null; completedAt: string | null;
}
export interface FileDiff { path: string; kind: 'CREATE' | 'UPDATE' | 'DELETE'; before: string | null; after: string | null }
export interface ProposalDetail { proposal: Proposal; files: FileDiff[] }
function nullableText(value: unknown, max: number): string | null {
  if (value === null) return null;
  if (typeof value !== 'string' || value.length > max) return invalid(); return value;
}
function proposal(value: unknown, taskId: string): Proposal {
  if (!object(value)) return invalid();
  const state = proposalStates.find((item) => item === value.state);
  if (!state || value.sequence === '0' || state === 'APPLIED' && (value.resultRevision === null || value.changed === null)
    || value.taskId !== taskId || value.mode !== 'ASK' && value.mode !== 'AUTO'
    || typeof value.fileCount !== 'number' || !Number.isInteger(value.fileCount) || value.fileCount < 1 || value.fileCount > 32
    || value.changed !== null && typeof value.changed !== 'boolean'
    || value.decisionType !== null && value.decisionType !== 'APPROVE' && value.decisionType !== 'REJECT') return invalid();
  return { id: uuid(value.id), sequence: sequence(value.sequence), taskId: uuid(value.taskId), state, mode: value.mode,
    baseRevision: sequence(value.baseRevision), fileCount: value.fileCount, resultRevision: value.resultRevision === null ? null : sequence(value.resultRevision),
    changed: value.changed, decisionType: value.decisionType, decisionReason: nullableText(value.decisionReason, 2048), failureCode: nullableText(value.failureCode, 128),
    createdAt: date(value.createdAt), decidedAt: value.decidedAt === null ? null : date(value.decidedAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
export async function proposals(owner: string, taskId: string, before: string, signal: AbortSignal): Promise<{ items: Proposal[]; nextCursor: string | null }> {
  sequence(before);
  const value = await request(`/api/copilot-tasks/${path(taskId)}/proposals?before=${before}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 20) return invalid();
  let previous = before === '0' ? 9223372036854775808n : BigInt(before);
  const items = value.items.map((entry: unknown) => { const result = proposal(entry, taskId);
    if (BigInt(result.sequence) >= previous) return invalid(); previous = BigInt(result.sequence); return result; });
  const nextCursor = value.nextCursor === null ? null : sequence(value.nextCursor);
  if (nextCursor !== null && nextCursor !== items.at(-1)?.sequence) return invalid();
  return { items, nextCursor };
}
export async function proposalStatus(owner: string, taskId: string, id: string, signal: AbortSignal): Promise<Proposal> {
  const result = proposal(await request(`/api/copilot-proposals/${path(id)}/status`, signal, { headers: ownerHeaders(owner) }), taskId);
  if (result.id !== id) return invalid(); return result;
}
export async function proposalDetail(owner: string, taskId: string, id: string, signal: AbortSignal): Promise<ProposalDetail> {
  const value = await request(`/api/copilot-proposals/${path(id)}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.files)) return invalid();
  const summary = proposal(value.proposal, taskId);
  if (summary.id !== id || value.files.length !== summary.fileCount) return invalid();
  const paths = new Set<string>(); let length = 0;
  const files = value.files.map((entry: unknown): FileDiff => {
    if (!object(entry) || typeof entry.path !== 'string' || entry.path.length > 512 || !entry.path || (entry.path.includes('\\') || [...entry.path].some((character) => character.charCodeAt(0) < 32))
      || paths.has(entry.path) || entry.kind !== 'CREATE' && entry.kind !== 'UPDATE' && entry.kind !== 'DELETE') return invalid();
    paths.add(entry.path);
    const before = nullableText(entry.before, 1048576); const after = nullableText(entry.after, 1048576);
    if (entry.kind === 'CREATE' && (before !== null || after === null) || entry.kind === 'DELETE' && (before === null || after !== null)
      || entry.kind === 'UPDATE' && (before === null || after === null)) return invalid();
    length += (before?.length ?? 0) + (after?.length ?? 0); if (length > 12 * 1048576) return invalid();
    return { path: entry.path, kind: entry.kind, before, after };
  });
  return { proposal: summary, files };
}
export async function decide(owner: string, taskId: string, id: string, credential: string, decision: 'APPROVE' | 'REJECT', reason: string | null, key: string, signal: AbortSignal): Promise<Proposal> {
  const result = proposal(await post(`/api/copilot-proposals/${path(id)}/decision`, { decision, reason }, signal, key, credential, owner), taskId);
  if (result.id !== id) return invalid(); return result;
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
