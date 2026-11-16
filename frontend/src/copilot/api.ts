import { ApiError, events, object, ownerHeaders, post, request } from '../api/request';
import type { ReferenceSelection } from './referenceApi';

const states = ['QUEUED', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD', 'STOPPING', 'STOPPED', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'] as const;
export interface Task { conversationId?: string; id: string; exerciseId: string; state: typeof states[number]; reason: string | null; stopRequested: boolean; createdAt: string; completedAt: string | null }
export interface Snapshot { task: Task; lastSequence: string; textHistory: boolean; partial: { turnId: string | null; sequence: string; text: string; truncated: boolean; thinking?: string; thinkingTruncated?: boolean } }
export interface Message { sequence: string; id: string; taskId: string; kind: 'USER' | 'ASSISTANT' | 'TOOL'; text: string; thinking?: string }
export interface Messages { messages: Message[]; nextCursor: string | null }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) return invalid(); return value; }
function sequence(value: unknown): string { if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n) return invalid(); return value; }
function thinkingText(value: unknown, maximum: number): string {
  if (value === undefined) return '';
  if (typeof value !== 'string' || value.length > maximum) return invalid();
  return value;
}
function date(value: unknown): string { if (typeof value !== 'string' || value.length > 64 || !Number.isFinite(Date.parse(value))) return invalid(); return value; }
function task(value: unknown, exercise: string, conversation?: string): Task {
  if (!object(value)) return invalid();
  const state = states.find((item) => item === value.state);
  if (!state || value.exerciseId !== exercise || typeof value.stopRequested !== 'boolean'
    || value.reason !== null && (typeof value.reason !== 'string' || value.reason.length > 128)) return invalid();
  const conversationId = uuid(value.conversationId ?? value.exerciseId);
  if (conversation !== undefined && conversationId !== conversation) return invalid();
  return { conversationId, id: uuid(value.id), exerciseId: uuid(value.exerciseId), state, reason: value.reason, stopRequested: value.stopRequested,
    createdAt: date(value.createdAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
export { task as parseTask };
function snapshot(value: unknown, exercise: string, conversation?: string): Snapshot {
  if (!object(value) || typeof value.textHistory !== 'boolean' || !object(value.partial)) return invalid();
  const partial = value.partial;
  if (typeof partial.text !== 'string' || partial.text.length > 8192 || typeof partial.truncated !== 'boolean'
    || partial.thinkingTruncated !== undefined && typeof partial.thinkingTruncated !== 'boolean') return invalid();
  return { task: task(value.task, exercise, conversation), lastSequence: sequence(value.lastSequence), textHistory: value.textHistory,
    partial: { turnId: partial.turnId === null ? null : uuid(partial.turnId), sequence: sequence(partial.sequence), text: partial.text, truncated: partial.truncated,
      thinking: thinkingText(partial.thinking, 8192), thinkingTruncated: partial.thinkingTruncated ?? false } };
}
function path(id: string) { return encodeURIComponent(uuid(id)); }
export interface Conversation { id: string; exerciseId: string; initial: boolean; createdAt: string; forkSource?: string; forkMessage?: string }
function conversation(value: unknown, exercise: string): Conversation {
  if (!object(value) || value.exerciseId !== exercise || typeof value.initial !== 'boolean') return invalid();
  const id = uuid(value.id);
  if (value.initial !== (id === exercise)) return invalid();
  if ((value.forkSource == null) !== (value.forkMessage == null) || (value.initial && value.forkSource != null)) return invalid();
  return { id, exerciseId: exercise, initial: value.initial, createdAt: date(value.createdAt),
    ...(value.forkSource == null ? {} : { forkSource: uuid(value.forkSource), forkMessage: uuid(value.forkMessage) }) };
}
export async function conversations(owner: string, exercise: string, signal: AbortSignal): Promise<Conversation[]> {
  const value = await request(`/api/exercises/${path(exercise)}/copilot/conversations`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length < 1 || value.length > 100) return invalid();
  const items = value.map((item: unknown) => conversation(item, exercise));
  if (new Set(items.map(item => item.id)).size !== items.length || items.filter(item => item.initial).length !== 1) return invalid();
  return items;
}
export async function newConversation(owner: string, exercise: string, credential: string, key: string, signal: AbortSignal): Promise<Conversation> {
  const result = conversation(await post(`/api/exercises/${path(exercise)}/copilot/conversations`, {}, signal, key, credential, owner), exercise);
  if (result.initial || result.forkSource) return invalid();
  return result;
}
export async function forkConversation(owner: string, exercise: string, source: string, message: string, credential: string, key: string, signal: AbortSignal): Promise<Conversation> {
  const result = conversation(await post(`/api/exercises/${path(exercise)}/copilot/conversations/fork`,
    { sourceConversationId: uuid(source), throughMessageId: uuid(message) }, signal, key, credential, owner), exercise);
  if (result.initial || result.forkSource !== source || result.forkMessage !== message) return invalid();
  return result;
}
export function active(task: Task | null | undefined) { return !!task && ['QUEUED', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD', 'STOPPING'].includes(task.state); }
export async function latest(owner: string, exercise: string, signal: AbortSignal, conversation?: string): Promise<Snapshot | null> {
  const value = await request(`/api/exercises/${path(exercise)}/copilot/tasks/latest${conversation ? `?conversation=${path(conversation)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  return value === null ? null : snapshot(value, exercise, conversation ?? exercise);
}
export async function start(owner: string, exercise: string, credential: string, text: string, key: string, signal: AbortSignal, skillIds: string[] = [], conversation?: string, references: ReferenceSelection[] = []): Promise<Task> {
  return task(await post(`/api/exercises/${path(exercise)}/copilot/tasks`, { text, feeAcknowledged: true, ...(references.length ? { references: references.map(item => ({ ...item })) } : {}), ...(skillIds.length ? { skillIds: [...skillIds] } : {}), ...(conversation ? { conversationId: conversation } : {}) }, signal, key, credential, owner), exercise, conversation ?? exercise);
}
export async function stop(owner: string, exercise: string, id: string, key: string, signal: AbortSignal): Promise<Task> {
  const result = task(await post(`/api/copilot-tasks/${path(id)}/stop`, {}, signal, key, undefined, owner), exercise);
  if (result.id !== id) return invalid(); return result;
}
export async function messages(owner: string, exercise: string, after: string, signal: AbortSignal, conversation?: string): Promise<Messages> {
  if (after !== '-1') sequence(after);
  const value = await request(`/api/exercises/${path(exercise)}/copilot/messages?after=${encodeURIComponent(after)}${conversation ? `&conversation=${path(conversation)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.messages) || value.messages.length > 100) return invalid();
  let previous = BigInt(after); let characters = 0;
  const items = value.messages.map((entry: unknown): Message => {
    if (!object(entry) || !['USER', 'ASSISTANT', 'TOOL'].includes(String(entry.kind)) || typeof entry.text !== 'string' || entry.text.length > 512 * 1024) return invalid();
    const seq = sequence(entry.sequence);
    if (BigInt(seq) <= previous) return invalid(); previous = BigInt(seq);
    const thinking = thinkingText(entry.thinking, 524288);
    characters += entry.text.length + thinking.length; if (characters > 524288) return invalid();
    const kind = entry.kind === 'USER' ? 'USER' : entry.kind === 'ASSISTANT' ? 'ASSISTANT' : 'TOOL';
    return { sequence: seq, id: uuid(entry.id), taskId: uuid(entry.taskId), kind, text: entry.text, thinking };
  });
  const nextCursor = value.nextCursor === null ? null : sequence(value.nextCursor);
  if (nextCursor !== null && nextCursor !== items.at(-1)?.sequence) return invalid();
  return { messages: items, nextCursor };
}
export interface Steer {
  id: string; sequence: string; taskId: string; exerciseId: string; actorId: string; text: string;
  state: 'RECEIVED' | 'APPLIED' | 'NOT_APPLIED'; turnId: string | null; reason: string | null; createdAt: string; completedAt: string | null;
}
export interface Steers { items: Steer[]; nextCursor: string | null }
function steerResponse(value: unknown, owner: string, exercise: string, taskId: string): Steer {
  if (!object(value) || value.actorId !== owner || value.exerciseId !== exercise || value.taskId !== taskId
    || typeof value.text !== 'string' || !value.text.trim() || new globalThis.TextEncoder().encode(value.text).length > 4096
    || value.reason !== null && (typeof value.reason !== 'string' || value.reason.length > 64)
    || value.state !== 'RECEIVED' && value.state !== 'APPLIED' && value.state !== 'NOT_APPLIED' || value.sequence === '0') return invalid();
  if (value.state === 'RECEIVED' && (value.turnId !== null || value.completedAt !== null || value.reason !== null)
    || value.state === 'APPLIED' && (value.turnId === null || value.completedAt === null || value.reason !== null)
    || value.state === 'NOT_APPLIED' && (value.turnId !== null || value.completedAt === null || typeof value.reason !== 'string' || !value.reason || value.reason.length > 64)) return invalid();
  return { id: uuid(value.id), sequence: sequence(value.sequence), taskId: uuid(value.taskId), exerciseId: uuid(value.exerciseId), actorId: uuid(value.actorId),
    text: value.text, state: value.state, turnId: value.turnId === null ? null : uuid(value.turnId), reason: value.reason,
    createdAt: date(value.createdAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
export async function steers(owner: string, exercise: string, taskId: string, before: string, signal: AbortSignal): Promise<Steers> {
  sequence(before);
  const value = await request(`/api/copilot-tasks/${path(taskId)}/steers?before=${encodeURIComponent(before)}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 20) return invalid();
  let previous = before === '0' ? 9223372036854775808n : BigInt(before); const ids = new Set<string>();
  const items = value.items.map((entry: unknown) => {
    const item = steerResponse(entry, owner, exercise, taskId);
    if (BigInt(item.sequence) >= previous || ids.has(item.id)) return invalid();
    previous = BigInt(item.sequence); ids.add(item.id); return item;
  });
  const nextCursor = value.nextCursor === null ? null : sequence(value.nextCursor);
  if (nextCursor !== null && (items.length !== 20 || nextCursor !== items.at(-1)?.sequence)) return invalid();
  return { items, nextCursor };
}
export async function sendSteer(owner: string, exercise: string, taskId: string, credential: string, text: string, key: string, signal: AbortSignal): Promise<Steer> {
  const value = steerResponse(await post(`/api/copilot-tasks/${path(taskId)}/steers`, { text }, signal, key, credential, owner), owner, exercise, taskId);
  if (value.text !== text) return invalid(); return value;
}
const compactionStates = ['WAITING', 'RUNNING', 'APPLIED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'NOT_NEEDED'] as const;
export interface Compaction {
  id: string; sequence: string; exerciseId: string; trigger: 'MANUAL' | 'AUTO'; state: typeof compactionStates[number];
  reason: string | null; attempts: number; stopRequested: boolean; sourceVersion: string | null; throughSequence: string | null;
  createdAt: string; completedAt: string | null;
}
export interface Context { exerciseId: string; version: string; summaryId: string | null; throughSequence: string; summary: string | null; requests: Compaction[] }
function compaction(value: unknown, exercise: string): Compaction {
  if (!object(value) || value.exerciseId !== exercise || value.trigger !== 'MANUAL' && value.trigger !== 'AUTO'
    || typeof value.attempts !== 'number' || !Number.isInteger(value.attempts) || value.attempts < 0 || value.attempts > 2
    || typeof value.stopRequested !== 'boolean' || value.reason !== null && (typeof value.reason !== 'string' || !value.reason || value.reason.length > 128)) return invalid();
  const state = compactionStates.find((entry) => entry === value.state);
  if (!state || (state === 'WAITING' || state === 'RUNNING') !== (value.completedAt === null) || value.sequence === '0') return invalid();
  const sourceVersion = value.sourceVersion === null ? null : sequence(value.sourceVersion);
  const throughSequence = value.throughSequence === null ? null : sequence(value.throughSequence);
  if ((sourceVersion === null) !== (throughSequence === null)
    || state === 'APPLIED' && (sourceVersion === null || throughSequence === '0' || value.attempts === 0 || value.stopRequested || value.reason !== null)) return invalid();
  return { id: uuid(value.id), sequence: sequence(value.sequence), exerciseId: uuid(value.exerciseId), trigger: value.trigger, state,
    reason: value.reason, attempts: value.attempts, stopRequested: value.stopRequested, sourceVersion, throughSequence,
    createdAt: date(value.createdAt), completedAt: value.completedAt === null ? null : date(value.completedAt) };
}
export async function context(owner: string, exercise: string, signal: AbortSignal, conversation?: string): Promise<Context> {
  const value = await request(`/api/exercises/${path(exercise)}/copilot/context${conversation ? `?conversation=${path(conversation)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.exerciseId !== exercise || !Array.isArray(value.requests) || value.requests.length > 20
    || value.summary !== null && (typeof value.summary !== 'string' || !value.summary.trim() || new globalThis.TextEncoder().encode(value.summary).length > 32768)) return invalid();
  const version = sequence(value.version); const throughSequence = sequence(value.throughSequence);
  const summaryId = value.summaryId === null ? null : uuid(value.summaryId);
  if (version === '0' ? summaryId !== null || value.summary !== null || throughSequence !== '0' : summaryId === null || value.summary === null || throughSequence === '0') return invalid();
  let previous = 9223372036854775808n; const ids = new Set<string>();
  const requests = value.requests.map((entry: unknown) => {
    const item = compaction(entry, exercise);
    if (BigInt(item.sequence) >= previous || ids.has(item.id)) return invalid();
    previous = BigInt(item.sequence); ids.add(item.id); return item;
  });
  if (requests.filter((entry) => entry.state === 'WAITING' || entry.state === 'RUNNING').length > 1) return invalid();
  return { exerciseId: exercise, version, summaryId, throughSequence, summary: value.summary, requests };
}
export async function compact(owner: string, exercise: string, credential: string, key: string, signal: AbortSignal, conversation?: string): Promise<Compaction> {
  const result = compaction(await post(`/api/exercises/${path(exercise)}/copilot/compactions`, { feeAcknowledged: true, ...(conversation ? { conversationId: conversation } : {}) }, signal, key, credential, owner), exercise);
  if (result.trigger !== 'MANUAL') return invalid(); return result;
}
export async function cancelCompaction(owner: string, exercise: string, id: string, key: string, signal: AbortSignal): Promise<Compaction> {
  const result = compaction(await post(`/api/exercises/${path(exercise)}/copilot/compactions/${path(id)}/stop`, {}, signal, key, undefined, owner), exercise);
  if (result.id !== id) return invalid(); return result;
}
const testResults = ['PASSED', 'FAILED', 'TIMED_OUT', 'STOPPED', 'ENVIRONMENT_ERROR', 'INTERRUPTED'] as const;
export interface TestRun {
  toolId: string; taskId: string; testTaskId: string; revision: string; target: 'base' | 'challenge'; state: 'WAITING' | 'RESOLVED';
  resultState: typeof testResults[number] | null; createdAt: string; completedAt: string | null;
}
export async function tests(owner: string, taskId: string, signal: AbortSignal): Promise<TestRun[]> {
  const value = await request(`/api/copilot-tasks/${path(taskId)}/tests`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(value) || value.length > 20) return invalid();
  const ids = new Set<string>();
  return value.map((entry: unknown): TestRun => {
    if (!object(entry) || entry.taskId !== taskId || (entry.target !== 'base' && entry.target !== 'challenge') || entry.state !== 'WAITING' && entry.state !== 'RESOLVED') return invalid();
    const resultState = testResults.find((state) => state === entry.resultState) ?? null;
    if (entry.state === 'WAITING' ? entry.resultState !== null || entry.completedAt !== null : !resultState || entry.completedAt === null) return invalid();
    const testTaskId = uuid(entry.testTaskId);
    if (ids.has(testTaskId)) return invalid(); ids.add(testTaskId);
    return { toolId: uuid(entry.toolId), taskId: uuid(entry.taskId), testTaskId, revision: sequence(entry.revision), target: entry.target, state: entry.state,
      resultState, createdAt: date(entry.createdAt), completedAt: entry.completedAt === null ? null : date(entry.completedAt) };
  });
}
export type ApprovalMode = 'ASK' | 'AUTO';
export interface ApprovalChange { exerciseId: string; id: string; previousMode: ApprovalMode; mode: ApprovalMode; version: string; actorId: string; createdAt: string }
export interface ApprovalModeSnapshot { exerciseId: string; mode: ApprovalMode; version: string; history: ApprovalChange[] }
function approvalModeValue(value: unknown): ApprovalMode { if (value !== 'ASK' && value !== 'AUTO') return invalid(); return value; }
function approvalChange(value: unknown, owner: string, exercise: string): ApprovalChange {
  if (!object(value) || value.exerciseId !== exercise || value.actorId !== owner || value.version === '0') return invalid();
  return { exerciseId: uuid(value.exerciseId), id: uuid(value.id), previousMode: approvalModeValue(value.previousMode), mode: approvalModeValue(value.mode),
    version: sequence(value.version), actorId: uuid(value.actorId), createdAt: date(value.createdAt) };
}
export async function approvalMode(owner: string, exercise: string, signal: AbortSignal): Promise<ApprovalModeSnapshot> {
  const value = await request(`/api/exercises/${path(exercise)}/copilot/approval-mode`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.exerciseId !== exercise || !Array.isArray(value.history) || value.history.length > 20) return invalid();
  const mode = approvalModeValue(value.mode); const version = sequence(value.version);
  let previous = BigInt(version) + 1n; const ids = new Set<string>();
  const history = value.history.map((entry: unknown) => {
    const change = approvalChange(entry, owner, exercise);
    if (BigInt(change.version) >= previous || ids.has(change.id)) return invalid();
    previous = BigInt(change.version); ids.add(change.id); return change;
  });
  if (version === '0' ? mode !== 'ASK' || history.length !== 0 : history[0]?.version !== version || history[0]?.mode !== mode) return invalid();
  return { exerciseId: uuid(value.exerciseId), mode, version, history };
}
export async function changeApprovalMode(owner: string, exercise: string, credential: string, mode: ApprovalMode, expectedVersion: string,
  key: string, signal: AbortSignal): Promise<ApprovalChange> {
  const result = approvalChange(await post(`/api/exercises/${path(exercise)}/copilot/approval-mode`,
    { mode: approvalModeValue(mode), expectedVersion: sequence(expectedVersion) }, signal, key, credential, owner), owner, exercise);
  if (result.mode !== mode || BigInt(result.version) !== BigInt(expectedVersion) + 1n) return invalid();
  return result;
}
const proposalStates = ['PENDING', 'APPLYING', 'APPLIED', 'REJECTED', 'INVALIDATED', 'FAILED', 'CANCELLED'] as const;
export interface Proposal {
  id: string; sequence: string; taskId: string; state: typeof proposalStates[number]; mode: 'ASK' | 'AUTO'; modeVersion: string; baseRevision: string;
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
    || value.taskId !== taskId || value.mode !== 'ASK' && value.mode !== 'AUTO' || value.mode === 'AUTO' && value.modeVersion === '0'
    || typeof value.fileCount !== 'number' || !Number.isInteger(value.fileCount) || value.fileCount < 1 || value.fileCount > 32
    || value.changed !== null && typeof value.changed !== 'boolean'
    || value.decisionType !== null && value.decisionType !== 'APPROVE' && value.decisionType !== 'REJECT') return invalid();
  return { id: uuid(value.id), sequence: sequence(value.sequence), taskId: uuid(value.taskId), state, mode: value.mode, modeVersion: sequence(value.modeVersion),
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
