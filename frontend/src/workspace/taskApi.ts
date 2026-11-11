import { ApiError, events, object, post, request } from '../api/request';

export interface TestTask {
  taskId: string; exerciseId: string; executionInstanceId: string; workspaceInstanceId: string;
  revision: string; target: string; state: string; reason: string; startedAt: string | null; finishedAt: string | null;
  exitCode: number | null; quiescent: boolean; cleanup: string; copyCleanup: string;
  droppedBytes: string; logsIncomplete: boolean; logs: { channel: string; text: string }[];
  problem: { problemId: string; version: string; packageSha256: string; executionImageDigest: string; platform: string };
  evidence: { kind: string; sha256: string; bytes: number } | null;
  streamId: string | null; cursor: string | null; sequence: string | null; firstRetainedSequence: string | null;
}
/** Do not let a delayed REST response roll back an already observed stream. */
export function latestTaskSnapshot(current: TestTask | undefined, incoming: TestTask): TestTask {
  if (current?.taskId === incoming.taskId && current.executionInstanceId === incoming.executionInstanceId) {
    if (current.finishedAt && !incoming.finishedAt) return current;
    if (current.streamId && current.streamId === incoming.streamId && current.sequence !== null && incoming.sequence !== null
      && BigInt(current.sequence) > BigInt(incoming.sequence)) return current;
  }
  return incoming;
}
const states = ['ACCEPTED', 'STARTING', 'RUNNING', 'STOPPING', 'PASSED', 'FAILED', 'TIMED_OUT', 'STOPPED', 'ENVIRONMENT_ERROR', 'INTERRUPTED'];
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function timestamp(value: unknown): value is string | null { return value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value))); }
function parse(value: unknown, exercise: string, workspace: string): TestTask {
  if (!object(value) || value.exerciseId !== exercise || value.workspaceInstanceId !== workspace
    || typeof value.taskId !== 'string' || typeof value.executionInstanceId !== 'string'
    || typeof value.revision !== 'string' || !/^(0|[1-9]\d*)$/.test(value.revision) || value.target !== 'base'
    || typeof value.state !== 'string' || !states.includes(value.state) || typeof value.reason !== 'string'
    || !timestamp(value.startedAt) || !timestamp(value.finishedAt)
    || (value.exitCode !== null && (typeof value.exitCode !== 'number' || !Number.isInteger(value.exitCode) || value.exitCode < 0 || value.exitCode > 255))
    || typeof value.quiescent !== 'boolean' || typeof value.cleanup !== 'string' || !['PENDING', 'SUCCEEDED', 'FAILED'].includes(value.cleanup)
    || typeof value.copyCleanup !== 'string' || !['PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'NOT_CREATED'].includes(value.copyCleanup)
    || typeof value.droppedBytes !== 'string' || !/^\d+$/.test(value.droppedBytes) || typeof value.logsIncomplete !== 'boolean'
    || !Array.isArray(value.logs) || value.logs.length > 4096) return invalid();
  const problem = value.problem;
  if (!object(problem) || typeof problem.problemId !== 'string' || typeof problem.version !== 'string'
    || typeof problem.packageSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(problem.packageSha256)
    || typeof problem.executionImageDigest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(problem.executionImageDigest)
    || problem.platform !== 'linux/amd64') return invalid();
  let evidence: TestTask['evidence'] = null;
  if (value.evidence !== null) {
    const source = value.evidence;
    if (!object(source) || typeof source.kind !== 'string' || !['SUREFIRE_XML', 'COMPILATION_DIAGNOSTICS'].includes(source.kind)
      || typeof source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(source.sha256)
      || typeof source.bytes !== 'number' || !Number.isInteger(source.bytes) || source.bytes <= 0
      || source.bytes > (source.kind === 'SUREFIRE_XML' ? 2097152 : 16384)) return invalid();
    evidence = { kind: source.kind, sha256: source.sha256, bytes: source.bytes };
  }
  if (['PASSED', 'FAILED'].includes(value.state) && evidence === null) return invalid();
  let retained = 0;
  const logs = value.logs.map((entry: unknown) => {
    if (!object(entry) || (entry.channel !== 'stdout' && entry.channel !== 'stderr') || typeof entry.text !== 'string' || entry.text.length > 49152) return invalid();
    retained += entry.text.length;
    if (retained > 3 * 1048576) return invalid(); // UTF-8 replacement can expand retained binary output.
    return { channel: entry.channel, text: entry.text };
  });
  const streamId = value.streamId ?? null; const cursor = value.cursor ?? null;
  const sequence = value.sequence ?? null; const firstRetainedSequence = value.firstRetainedSequence ?? null;
  if ((cursor !== null && typeof cursor !== 'string') || (sequence !== null && typeof sequence !== 'string')
    || (firstRetainedSequence !== null && typeof firstRetainedSequence !== 'string')) return invalid();
  if (streamId === null && (cursor !== null || sequence !== null || firstRetainedSequence !== null)) return invalid();
  if (streamId !== null && (typeof streamId !== 'string' || streamId.length > 64 || typeof cursor !== 'string' || cursor.length > 160
    || typeof sequence !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(sequence)
    || typeof firstRetainedSequence !== 'string' || !/^[1-9]\d{0,18}$/.test(firstRetainedSequence))) return invalid();
  return { streamId, cursor, sequence, firstRetainedSequence, taskId: value.taskId, exerciseId: exercise, executionInstanceId: value.executionInstanceId, workspaceInstanceId: workspace,
    revision: value.revision, target: value.target, state: value.state, reason: value.reason, startedAt: value.startedAt, finishedAt: value.finishedAt,
    exitCode: value.exitCode, quiescent: value.quiescent, cleanup: value.cleanup, copyCleanup: value.copyCleanup,
    droppedBytes: value.droppedBytes, logsIncomplete: value.logsIncomplete, logs,
    problem: { problemId: problem.problemId, version: problem.version, packageSha256: problem.packageSha256,
      executionImageDigest: problem.executionImageDigest, platform: problem.platform }, evidence };
}
export interface TaskEvent {
  type: 'log' | 'progress' | 'task_result' | 'log_truncated'; sequence: string; cursor: string;
  payload: { channel?: 'stdout' | 'stderr'; data?: string; state?: string; replaceLogs?: boolean; droppedBytes?: string };
}
export function watchTask(task: TestTask, receive: (event: TaskEvent) => void, reset: () => void, signal: AbortSignal): () => void {
  if (!task.cursor || !task.streamId) return () => {};
  return events(`/api/exercises/${encodeURIComponent(task.exerciseId)}/tasks/${encodeURIComponent(task.taskId)}/events?after=${encodeURIComponent(task.cursor)}`,
    ['log', 'progress', 'task_result', 'log_truncated', 'reset_required'], (type, data, id) => {
      try {
        if (type === 'reset_required' || data.length > 65536 || id.length > 160 || !id) return reset();
        const event: unknown = JSON.parse(data);
        if (!object(event) || event.schemaVersion !== 1 || event.taskId !== task.taskId || event.executionInstanceId !== task.executionInstanceId
          || event.streamId !== task.streamId || event.type !== type || typeof event.sequence !== 'string' || !/^[1-9]\d{0,18}$/.test(event.sequence)
          || typeof event.occurredAt !== 'string' || !Number.isFinite(Date.parse(event.occurredAt)) || !object(event.payload)) return reset();
        const payload = event.payload;
        if (type === 'log' && (payload.channel === 'stdout' || payload.channel === 'stderr') && typeof payload.data === 'string' && payload.data.length <= 16384) {
          receive({ type, sequence: event.sequence, cursor: id, payload: { channel: payload.channel, data: payload.data } });
        } else if ((type === 'progress' || type === 'task_result') && typeof payload.state === 'string' && states.includes(payload.state)
          && (type === 'task_result' || typeof payload.replaceLogs === 'boolean')) {
          receive({ type, sequence: event.sequence, cursor: id, payload: { state: payload.state, replaceLogs: payload.replaceLogs === true } });
        } else if (type === 'log_truncated' && typeof payload.droppedBytes === 'string' && /^\d{1,20}$/.test(payload.droppedBytes)) {
          receive({ type, sequence: event.sequence, cursor: id, payload: { droppedBytes: payload.droppedBytes } });
        } else reset();
      } catch { reset(); }
    }, reset, signal);
}
export async function startTest(exercise: string, workspace: string, revision: string, key: string, credential: string, signal: AbortSignal): Promise<TestTask> {
  const result = parse(await post(`/api/exercises/${encodeURIComponent(exercise)}/tests`, { expectedRevision: revision, target: 'base' }, signal, key, credential), exercise, workspace);
  if (result.revision !== revision) return invalid();
  return result;
}
export async function stopTest(exercise: string, workspace: string, id: string, key: string, credential: string, signal: AbortSignal): Promise<TestTask> {
  const result = parse(await post(`/api/exercises/${encodeURIComponent(exercise)}/tasks/${encodeURIComponent(id)}/stop`, {}, signal, key, credential), exercise, workspace);
  if (result.taskId !== id) return invalid();
  return result;
}
export async function recentTests(exercise: string, workspace: string, signal: AbortSignal): Promise<TestTask[]> {
  const value = await request(`/api/exercises/${encodeURIComponent(exercise)}/tasks`, signal);
  if (!Array.isArray(value) || value.length > 20) return invalid();
  return value.map((item: unknown) => parse(item, exercise, workspace));
}
export async function testTask(exercise: string, workspace: string, id: string, signal: AbortSignal): Promise<TestTask> {
  const result = parse(await request(`/api/exercises/${encodeURIComponent(exercise)}/tasks/${encodeURIComponent(id)}`, signal), exercise, workspace);
  if (result.taskId !== id) return invalid();
  return result;
}
