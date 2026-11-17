import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { parseCommand, type CommandTask } from './commandApi';

export interface TerminalSession {
  commandId: string;
  ticket: string;
  webSocketPath: string;
  protocol: 'codecraft.terminal.v1';
  gateEpoch: string;
  expiresAt: string;
}

export interface TerminalAudit {
  commandId: string;
  state: string;
  createdAt: string;
  openedAt: string | null;
  closedAt: string | null;
  baseRevision: string | null;
  synchronizedRevision: string | null;
  synchronization: string;
  reason: string | null;
  pruned: boolean;
}

export type TerminalServerEvent =
  | { type: 'opened'; commandId: string }
  | { type: 'error'; code: string }
  | {
    type: 'exit';
    revision: string;
    state: string;
    synchronization: string;
    reason: string;
    hasExitCode: boolean;
    exitCode: number | null;
  };

const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const nullableTimestamp = (value: unknown): value is string | null => value === null || timestamp(value);
const nullableRevision = (value: unknown): value is string | null => value === null || (typeof value === 'string' && /^\d+$/.test(value));

export function parseSession(value: unknown, exerciseId: string): TerminalSession {
  const expectedPath = `/api/exercises/${exerciseId}/terminal/ws`;
  if (!object(value) || typeof value.commandId !== 'string' || !value.commandId
    || typeof value.ticket !== 'string' || !value.ticket || /[\s,]/.test(value.ticket)
    || value.webSocketPath !== expectedPath
    || value.protocol !== 'codecraft.terminal.v1'
    || typeof value.gateEpoch !== 'string' || !/^\d+$/.test(value.gateEpoch)
    || !timestamp(value.expiresAt)) {
    return invalid();
  }
  return {
    commandId: value.commandId,
    ticket: value.ticket,
    webSocketPath: value.webSocketPath,
    protocol: 'codecraft.terminal.v1',
    gateEpoch: value.gateEpoch,
    expiresAt: value.expiresAt,
  };
}

export function parseAudit(value: unknown): TerminalAudit {
  if (!object(value) || typeof value.commandId !== 'string' || !value.commandId
    || typeof value.state !== 'string' || !value.state
    || !timestamp(value.createdAt)
    || !nullableTimestamp(value.openedAt)
    || !nullableTimestamp(value.closedAt)
    || !nullableRevision(value.baseRevision)
    || !nullableRevision(value.synchronizedRevision)
    || typeof value.synchronization !== 'string'
    || (value.reason !== null && typeof value.reason !== 'string')
    || typeof value.pruned !== 'boolean') {
    return invalid();
  }
  return {
    commandId: value.commandId,
    state: value.state,
    createdAt: value.createdAt,
    openedAt: value.openedAt,
    closedAt: value.closedAt,
    baseRevision: value.baseRevision,
    synchronizedRevision: value.synchronizedRevision,
    synchronization: value.synchronization,
    reason: value.reason,
    pruned: value.pruned,
  };
}

export function parseServerMessage(raw: string): TerminalServerEvent | null {
  let value: unknown;
  try { value = JSON.parse(raw); }
  catch { return null; }
  if (!object(value) || typeof value.type !== 'string') return null;
  if (value.type === 'opened' && typeof value.commandId === 'string' && value.commandId) {
    return { type: 'opened', commandId: value.commandId };
  }
  if (value.type === 'error' && typeof value.code === 'string' && value.code) {
    return { type: 'error', code: value.code };
  }
  if (value.type === 'exit'
    && typeof value.revision === 'string' && /^\d+$/.test(value.revision)
    && typeof value.state === 'string'
    && typeof value.synchronization === 'string'
    && (value.reason === undefined || value.reason === null || typeof value.reason === 'string')
    && typeof value.hasExitCode === 'boolean'
    && (value.exitCode === undefined || value.exitCode === null || (typeof value.exitCode === 'number' && Number.isInteger(value.exitCode)))) {
    return {
      type: 'exit',
      revision: value.revision,
      state: value.state,
      synchronization: value.synchronization,
      reason: typeof value.reason === 'string' ? value.reason : '',
      hasExitCode: value.hasExitCode,
      exitCode: value.hasExitCode && typeof value.exitCode === 'number' ? value.exitCode : null,
    };
  }
  return null;
}

export function terminalWsUrl(webSocketPath: string, location: Pick<Location, 'protocol' | 'host'>): string {
  if (!webSocketPath.startsWith('/api/exercises/') || webSocketPath.includes('?') || webSocketPath.includes('#')) {
    throw new ApiError(502, 'INVALID_RESPONSE');
  }
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}${webSocketPath}`;
}

export async function issueSession(owner: string, exerciseId: string, expectedRevision: string,
  key: string, credential: string, signal: AbortSignal): Promise<TerminalSession> {
  const path = `/api/exercises/${encodeURIComponent(exerciseId)}/terminal/session`;
  return parseSession(await post(path, { expectedRevision }, signal, key, credential, owner), exerciseId);
}

export async function syncSession(owner: string, exerciseId: string, workspaceId: string, commandId: string,
  key: string, signal: AbortSignal): Promise<CommandTask> {
  const path = `/api/exercises/${encodeURIComponent(exerciseId)}/terminal/sync`;
  return parseCommand(await post(path, { commandId }, signal, key, undefined, owner), exerciseId, workspaceId);
}

export async function sessions(owner: string, exerciseId: string, signal: AbortSignal): Promise<TerminalAudit[]> {
  const path = `/api/exercises/${encodeURIComponent(exerciseId)}/terminal/sessions`;
  const data = await request(path, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(data)) return invalid();
  return data.map(parseAudit);
}
