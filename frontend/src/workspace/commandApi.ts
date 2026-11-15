import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { parseFileDifferences, type FileDifference } from './fileDifference';

export interface CommandTask {
  id: string; exerciseId: string; workspaceInstanceId: string; executionInstanceId: string;
  revision: string; command: string; directory: string; state: string; reason: string;
  stopRequested: boolean; quiescent: boolean; synchronization: string; synchronizationReason: string;
  synchronizedRevision: string | null; changed: boolean; exitCode: number | null;
  logs: { channel: string; text: string }[]; droppedBytes: string; logsIncomplete: boolean;
  cleanup: string; copyCleanup: string; stateVersion: string; createdAt: string; completedAt: string | null;
}
export interface CommandHistory { commands: CommandTask[]; nextCursor: string | null }
export interface CommandInput { command: string; directory: string; expectedRevision: string }
export interface CommandChanges {
  commandId: string; exerciseId: string; workspaceInstanceId: string; executionInstanceId: string; baseRevision: string;
  changes: FileDifference[];
}
export type CommandOutcome = CommandTask | { retired: true };
const states = new Set(['ACCEPTED', 'STARTING', 'RUNNING', 'STOPPING', 'EXITED', 'TIMED_OUT', 'STOPPED', 'ENVIRONMENT_ERROR', 'INTERRUPTED']);
const synchronization = new Set(['PENDING', 'SYNCED', 'UNCHANGED', 'CONFLICT', 'REJECTED']);
const cleanup = new Set(['PENDING', 'SUCCEEDED', 'FAILED']);
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function id(value: unknown): value is string { return typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value); }
function counter(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n;
}
function timestamp(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)); }
export function parseCommand(value: unknown, exercise: string, workspace: string): CommandTask {
  if (!object(value) || value.exerciseId !== exercise || value.workspaceInstanceId !== workspace || !id(value.id) || !id(value.executionInstanceId)
    || !counter(value.revision) || !counter(value.stateVersion) || !counter(value.droppedBytes)
    || typeof value.command !== 'string' || value.command.length > 4096 || typeof value.directory !== 'string' || value.directory.length > 1024
    || typeof value.state !== 'string' || !states.has(value.state) || typeof value.reason !== 'string' || value.reason.length > 256
    || typeof value.synchronization !== 'string' || !synchronization.has(value.synchronization)
    || typeof value.synchronizationReason !== 'string' || value.synchronizationReason.length > 256
    || (value.synchronizedRevision !== null && !counter(value.synchronizedRevision))
    || typeof value.quiescent !== 'boolean' || typeof value.stopRequested !== 'boolean' || typeof value.changed !== 'boolean'
    || typeof value.logsIncomplete !== 'boolean' || typeof value.cleanup !== 'string' || !cleanup.has(value.cleanup)
    || typeof value.copyCleanup !== 'string' || !cleanup.has(value.copyCleanup)
    || !timestamp(value.createdAt) || (value.completedAt !== null && !timestamp(value.completedAt))
    || (value.exitCode !== null && (typeof value.exitCode !== 'number' || !Number.isInteger(value.exitCode) || value.exitCode < 0 || value.exitCode > 255))
    || !Array.isArray(value.logs) || value.logs.length > 4096) return invalid();
  if ((value.synchronization === 'SYNCED') !== (value.synchronizedRevision !== null)
    || (value.changed && value.synchronization !== 'SYNCED') || (value.quiescent && value.completedAt === null)
    || (value.synchronization !== 'PENDING' && !value.quiescent) || (value.state === 'EXITED' && value.exitCode === null)) return invalid();
  let size = 0;
  const logs = value.logs.map((entry: unknown) => {
    if (!object(entry) || !['stdout', 'stderr'].includes(String(entry.channel)) || typeof entry.channel !== 'string'
      || typeof entry.text !== 'string' || entry.text.length > 49152) return invalid();
    size += entry.text.length;
    if (size > 3 * 1048576) return invalid();
    return { channel: entry.channel, text: entry.text };
  });
  return { id: value.id, exerciseId: exercise, workspaceInstanceId: workspace, executionInstanceId: value.executionInstanceId,
    revision: value.revision, stateVersion: value.stateVersion, command: value.command, directory: value.directory,
    state: value.state, reason: value.reason, stopRequested: value.stopRequested, quiescent: value.quiescent,
    synchronization: value.synchronization, synchronizationReason: value.synchronizationReason, synchronizedRevision: value.synchronizedRevision,
    changed: value.changed, exitCode: value.exitCode, logs, droppedBytes: value.droppedBytes, logsIncomplete: value.logsIncomplete,
    cleanup: value.cleanup, copyCleanup: value.copyCleanup, createdAt: value.createdAt, completedAt: value.completedAt };
}

/** An older poll cannot undo a stop intent, exit, or confirmed synchronization. */
export function latest(current: CommandTask | undefined, incoming: CommandTask): CommandTask {
  if (!current || current.id !== incoming.id || current.executionInstanceId !== incoming.executionInstanceId) return incoming;
  const older = BigInt(incoming.stateVersion) < BigInt(current.stateVersion);
  const same = current.stateVersion === incoming.stateVersion;
  const regression = same && ((current.quiescent && !incoming.quiescent)
    || (current.synchronization !== 'PENDING' && incoming.synchronization === 'PENDING')
    || (current.state !== 'ACCEPTED' && incoming.state === 'ACCEPTED'));
  return { ...(older || regression ? current : incoming), stopRequested: current.stopRequested || incoming.stopRequested };
}
function base(exercise: string) { return `/api/exercises/${encodeURIComponent(exercise)}/commands`; }
export async function history(owner: string, exercise: string, workspace: string, after: string | null, signal: AbortSignal): Promise<CommandHistory> {
  const value = await request(`${base(exercise)}${after ? `?after=${encodeURIComponent(after)}` : ''}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.commands) || value.commands.length > 20 || (value.nextCursor !== null && !id(value.nextCursor))) return invalid();
  const commands = value.commands.map((entry: unknown) => parseCommand(entry, exercise, workspace));
  if (new Set(commands.map(item => item.id)).size !== commands.length) return invalid();
  return { commands, nextCursor: value.nextCursor };
}
export async function detail(owner: string, exercise: string, workspace: string, command: string, signal: AbortSignal): Promise<CommandTask> {
  const result = parseCommand(await request(`${base(exercise)}/${encodeURIComponent(command)}`, signal, { headers: ownerHeaders(owner) }), exercise, workspace);
  if (result.id !== command) return invalid();
  return result;
}
export async function start(owner: string, exercise: string, workspace: string, input: CommandInput, key: string, credential: string, signal: AbortSignal): Promise<CommandOutcome> {
  try {
    const result = parseCommand(await post(base(exercise), input, signal, key, credential, owner), exercise, workspace);
    if (result.command !== input.command || result.directory !== input.directory || result.revision !== input.expectedRevision) return invalid();
    return result;
  } catch (failure) {
    // This code is returned only after the exact request fingerprint matched a
    // durable retired receipt. It resolves uncertainty without replaying Bash.
    if (failure instanceof ApiError && failure.status === 410 && failure.code === 'COMMAND_HISTORY_PRUNED') return { retired: true };
    throw failure;
  }
}
export async function stop(owner: string, exercise: string, workspace: string, command: string, key: string, credential: string, signal: AbortSignal): Promise<CommandOutcome> {
  try {
    const result = parseCommand(await post(`${base(exercise)}/${encodeURIComponent(command)}/stop`, {}, signal, key, credential, owner), exercise, workspace);
    if (result.id !== command) return invalid();
    return result;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 410 && failure.code === 'COMMAND_HISTORY_PRUNED') return { retired: true };
    throw failure;
  }
}
export async function changes(owner: string, exercise: string, workspace: string, command: CommandTask, signal: AbortSignal): Promise<CommandChanges> {
  const value = await request(`${base(exercise)}/${encodeURIComponent(command.id)}/changes`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.commandId !== command.id || value.exerciseId !== exercise || value.workspaceInstanceId !== workspace
    || value.executionInstanceId !== command.executionInstanceId || value.baseRevision !== command.revision
    || !Array.isArray(value.changes) || value.changes.length > 400) return invalid();
  const files = parseFileDifferences(value.changes);
  return { commandId: command.id, exerciseId: exercise, workspaceInstanceId: workspace, executionInstanceId: command.executionInstanceId,
    baseRevision: command.revision, changes: files };
}
