import { ApiError, object, ownerHeaders, request } from '../api/request';
import { parseTask, type Task } from './api';

export interface Delegation { toolId: string; parentTaskId: string; conversationId: string; goal: string; state: 'WAITING' | 'RESOLVED'; child: Task; resultPayload: string | null; createdAt: string; completedAt: string | null }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) return invalid(); return value; }
function date(value: unknown): string { if (typeof value !== 'string' || value.length > 64 || !Number.isFinite(Date.parse(value))) return invalid(); return value; }
export async function list(owner: string, exercise: string, parent: string, signal: globalThis.AbortSignal): Promise<Delegation[]> {
  const value = await request(`/api/copilot-tasks/${uuid(parent)}/delegations`, signal, { headers: ownerHeaders(owner) });
    if (!Array.isArray(value) || value.length > 8) return invalid();
    const seen = new Set<string>(); const children = new Set<string>(); const conversations = new Set<string>();
    return value.map(row => {
      if (!object(row) || row.parentTaskId !== parent || typeof row.goal !== 'string' || !row.goal.trim() || row.goal.length > 4096
        || !['WAITING', 'RESOLVED'].includes(String(row.state)) || row.resultPayload !== null && (typeof row.resultPayload !== 'string' || row.resultPayload.length > 131072)) return invalid();
      const conversationId = uuid(row.conversationId); const toolId = uuid(row.toolId);
      if (seen.has(toolId)) return invalid(); seen.add(toolId);
      const child = parseTask(row.child, exercise, conversationId);
      if (child.id === parent || conversationId === exercise || children.has(child.id) || conversations.has(conversationId)
        || row.state === 'WAITING' && (row.resultPayload !== null || row.completedAt !== null)
        || row.state === 'RESOLVED' && (row.resultPayload === null || row.completedAt === null || !['SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED'].includes(child.state))) return invalid();
      children.add(child.id); conversations.add(conversationId);
      return { toolId, parentTaskId: uuid(row.parentTaskId), conversationId, goal: row.goal, state: row.state as Delegation['state'], child,
        resultPayload: row.resultPayload as string | null, createdAt: date(row.createdAt), completedAt: row.completedAt === null ? null : date(row.completedAt) };
    });
}
