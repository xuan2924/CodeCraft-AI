import { ApiError, object, ownerHeaders, request } from '../api/request';
export interface ProjectInstruction { path: string; scope: string; status: 'LOADED' | 'MISSING' | 'LIMIT_EXCEEDED' | 'READ_FAILED'; content: string | null }
export interface ProjectInstructions { exerciseId: string; revision: string; sources: ProjectInstruction[]; scopeTruncated: boolean }
export async function projectInstructions(owner: string, exercise: string, task: string, signal: AbortSignal): Promise<ProjectInstructions | null> {
  const value = await request(`/api/copilot-tasks/${encodeURIComponent(task)}/project-instructions`, signal, { headers: ownerHeaders(owner) });
  if (value === null) return null;
  const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
  if (!object(value) || value.exerciseId !== exercise || typeof value.revision !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value.revision)
    || BigInt(value.revision) > 9223372036854775807n || !Array.isArray(value.sources) || value.sources.length > 32 || typeof value.scopeTruncated !== 'boolean') return invalid();
  let bytes = 0;
  const sources = value.sources.map((entry: unknown): ProjectInstruction => {
    if (!object(entry) || typeof entry.path !== 'string' || entry.path.length > 4096 || !/^(?:[A-Za-z0-9_.-]+\/)*AGENTS\.md$/.test(entry.path)
      || entry.path.split('/').some(part => part === '.' || part === '..') || entry.scope !== (entry.path === 'AGENTS.md' ? '.' : entry.path.slice(0, entry.path.lastIndexOf('/')))
      || !['LOADED', 'MISSING', 'LIMIT_EXCEEDED', 'READ_FAILED'].includes(String(entry.status))) return invalid();
    if (entry.status === 'LOADED') {
      if (typeof entry.content !== 'string') return invalid();
      const size = new TextEncoder().encode(entry.content).length;
      if (size > 8192 || (bytes += size) > 32768) return invalid();
      return { path: entry.path, scope: entry.scope, status: 'LOADED', content: entry.content };
    }
    if (entry.content !== null || (entry.status !== 'MISSING' && entry.status !== 'LIMIT_EXCEEDED' && entry.status !== 'READ_FAILED')) return invalid();
    return { path: entry.path, scope: entry.scope, status: entry.status, content: null };
  });
  if (new Set(sources.map(source => source.path)).size !== sources.length) return invalid();
  return { exerciseId: exercise, revision: value.revision, sources, scopeTruncated: value.scopeTruncated };
}
