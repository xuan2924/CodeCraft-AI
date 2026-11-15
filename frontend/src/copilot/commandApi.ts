import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { parseCommand, type CommandTask } from '../workspace/commandApi';

export interface CommandResult { code: string; reason: string; result: CommandTask | null; logTruncated: boolean; omittedLogBytes: string }
export interface CommandProposal {
  id: string; sequence: string; taskId: string; exerciseId: string; workspaceInstanceId: string; baseRevision: string;
  command: string; directory: string; mode: 'ASK' | 'AUTO'; modeVersion: string; state: 'PENDING' | 'WAITING' | 'RESOLVED';
  commandTaskId: string | null; decision: 'APPROVE' | 'REJECT' | null; reason: string | null; result: CommandResult | null;
  createdAt: string; decidedAt: string | null; completedAt: string | null;
}
export interface CommandPage { commands: CommandProposal[]; nextBefore: string | null }
export interface Decision { decision: 'APPROVE' | 'REJECT'; reason: string | null }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): value is string { return typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value); }
function counter(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n;
}
function time(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)); }
function text(value: unknown, bytes: number): value is string {
  return typeof value === 'string' && value.length <= bytes && new globalThis.TextEncoder().encode(value).length <= bytes;
}
function parse(value: unknown, exercise: string, task: string): CommandProposal {
  if (!object(value) || !uuid(value.id) || value.taskId !== task || value.exerciseId !== exercise || !uuid(value.workspaceInstanceId)
    || !counter(value.sequence) || value.sequence === '0' || !counter(value.baseRevision) || !counter(value.modeVersion)
    || !text(value.command, 4096) || !value.command.trim() || !text(value.directory, 1024) || !value.directory
    || (value.mode !== 'ASK' && value.mode !== 'AUTO') || (value.state !== 'PENDING' && value.state !== 'WAITING' && value.state !== 'RESOLVED')
    || (value.commandTaskId !== null && !uuid(value.commandTaskId)) || (value.decision !== null && value.decision !== 'APPROVE' && value.decision !== 'REJECT')
    || (value.reason !== null && !text(value.reason, 2048)) || !time(value.createdAt)
    || (value.decidedAt !== null && !time(value.decidedAt)) || (value.completedAt !== null && !time(value.completedAt))) return invalid();
  let result: CommandResult | null = null;
  if (value.result !== null) {
    const raw = value.result;
    if (!object(raw) || typeof raw.code !== 'string' || !/^[A-Z_0-9]{1,64}$/.test(raw.code) || !text(raw.reason, 2048)
      || typeof raw.logTruncated !== 'boolean' || !counter(raw.omittedLogBytes)) return invalid();
    const execution = raw.result === null ? null : parseCommand(raw.result, exercise, value.workspaceInstanceId);
    if (execution && (execution.id !== value.commandTaskId || execution.revision !== value.baseRevision || execution.command !== value.command
      || execution.directory !== value.directory || !execution.quiescent || execution.synchronization === 'PENDING'
      || execution.cleanup !== 'SUCCEEDED' || execution.copyCleanup !== 'SUCCEEDED')) return invalid();
    result = { code: raw.code, reason: raw.reason, result: execution, logTruncated: raw.logTruncated, omittedLogBytes: raw.omittedLogBytes };
  }
  if ((value.state === 'RESOLVED') !== (result !== null) || (value.state === 'RESOLVED') !== (value.completedAt !== null)
    || (value.state === 'PENDING' && (value.commandTaskId !== null || value.decision !== null))
    || (value.state === 'WAITING' && (value.commandTaskId === null || value.decision !== 'APPROVE'))
    || ((value.decision === null) !== (value.decidedAt === null))) return invalid();
  return { id: value.id, sequence: value.sequence, taskId: task, exerciseId: exercise, workspaceInstanceId: value.workspaceInstanceId,
    baseRevision: value.baseRevision, command: value.command, directory: value.directory, mode: value.mode, modeVersion: value.modeVersion,
    state: value.state, commandTaskId: value.commandTaskId, decision: value.decision, reason: value.reason, result,
    createdAt: value.createdAt, decidedAt: value.decidedAt, completedAt: value.completedAt };
}
export function latest(current: CommandProposal | undefined, incoming: CommandProposal): CommandProposal {
  if (!current || current.id !== incoming.id) return incoming;
  const rank = { PENDING: 0, WAITING: 1, RESOLVED: 2 };
  return rank[current.state] >= rank[incoming.state] ? current : incoming;
}
export async function page(owner: string, exercise: string, task: string, before: string | null, signal: AbortSignal): Promise<CommandPage> {
  const value = await request(`/api/copilot-tasks/${encodeURIComponent(task)}/commands${before ? `?before=${encodeURIComponent(before)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.commands) || value.commands.length > 20 || (value.nextBefore !== null && !counter(value.nextBefore))) return invalid();
  const commands = value.commands.map((item: unknown) => parse(item, exercise, task));
  if (new Set(commands.map(item => item.id)).size !== commands.length || commands.some((item, index) =>
    (before !== null && BigInt(item.sequence) >= BigInt(before)) || (index > 0 && BigInt(item.sequence) >= BigInt(commands[index - 1]!.sequence)))) return invalid();
  if (value.nextBefore !== null && value.nextBefore !== commands.at(-1)?.sequence) return invalid();
  return { commands, nextBefore: value.nextBefore };
}
export async function detail(owner: string, exercise: string, task: string, id: string, signal: AbortSignal): Promise<CommandProposal> {
  const value = parse(await request(`/api/copilot-commands/${encodeURIComponent(id)}`, signal, { headers: ownerHeaders(owner) }), exercise, task);
  if (value.id !== id) return invalid();
  return value;
}
export async function decide(owner: string, exercise: string, task: string, id: string, input: Decision, key: string, credential: string, signal: AbortSignal): Promise<CommandProposal> {
  const value = parse(await post(`/api/copilot-commands/${encodeURIComponent(id)}/decision`, input, signal, key, credential, owner), exercise, task);
  if (value.id !== id || (value.decision !== null && value.decision !== input.decision)) return invalid();
  return value;
}
