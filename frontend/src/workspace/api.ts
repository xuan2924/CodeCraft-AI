import { ApiError, object, post, request } from '../api/request';

export interface Problem {
  problemVersionId: string; title: string; version: string; requirements: string;
  durationSeconds: number; editableRoots: string[]; acceptanceFiles: Record<string, string>;
}
export interface Exercise {
  exerciseId: string; problemVersionId: string; workspaceInstanceId: string; state: string;
  revision: string | null; paths: string[]; deadlineAt: string | null;
  preparationFailureReason: string | null; cleanupState: string | null;
}
export interface WorkspaceFile { workspaceInstanceId: string; revision: string; path: string; content: string }
function strings(value: unknown): value is string[] { return Array.isArray(value) && value.every((entry) => typeof entry === 'string'); }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
export async function problems(signal: AbortSignal): Promise<Problem[]> {
  const data = await request('/api/problems', signal);
  if (!Array.isArray(data)) return invalid();
  return data.map((value: unknown) => {
    if (!object(value) || typeof value.problemVersionId !== 'string' || typeof value.title !== 'string'
      || typeof value.version !== 'string' || typeof value.requirements !== 'string' || typeof value.durationSeconds !== 'number'
      || !strings(value.editableRoots) || !object(value.acceptanceFiles)) return invalid();
    const acceptanceFiles: Record<string, string> = {};
    for (const [path, content] of Object.entries(value.acceptanceFiles)) {
      if (typeof content !== 'string') return invalid();
      acceptanceFiles[path] = content;
    }
    return { problemVersionId: value.problemVersionId, title: value.title, version: value.version, requirements: value.requirements,
      durationSeconds: value.durationSeconds, editableRoots: value.editableRoots, acceptanceFiles };
  });
}
export async function current(signal: AbortSignal): Promise<Exercise | null> {
  let value: unknown;
  try { value = await request('/api/exercises/current', signal); }
  catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
  if (!object(value) || typeof value.exerciseId !== 'string' || typeof value.problemVersionId !== 'string'
    || typeof value.workspaceInstanceId !== 'string' || typeof value.state !== 'string'
    || !['PREPARING', 'READY', 'PREPARATION_FAILED', 'ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED'].includes(value.state)
    || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || (value.preparationFailureReason !== null && typeof value.preparationFailureReason !== 'string')
    || (value.cleanupState !== null && !['PENDING', 'SUCCEEDED', 'FAILED'].includes(String(value.cleanupState)))
    || !strings(value.paths) || (value.deadlineAt !== null && (typeof value.deadlineAt !== 'string' || !Number.isFinite(Date.parse(value.deadlineAt))))) return invalid();
  return { exerciseId: value.exerciseId, problemVersionId: value.problemVersionId, workspaceInstanceId: value.workspaceInstanceId,
    state: value.state, revision: value.revision, paths: value.paths, deadlineAt: value.deadlineAt,
    preparationFailureReason: value.preparationFailureReason, cleanupState: value.cleanupState === null ? null : String(value.cleanupState) };
}
export async function file(exercise: Exercise, path: string, signal: AbortSignal): Promise<WorkspaceFile> {
  const value = await request(`/api/exercises/${encodeURIComponent(exercise.exerciseId)}/files?path=${encodeURIComponent(path)}`, signal);
  if (!object(value) || value.workspaceInstanceId !== exercise.workspaceInstanceId || value.path !== path
    || value.revision !== exercise.revision || typeof value.content !== 'string') return invalid();
  return { workspaceInstanceId: exercise.workspaceInstanceId, revision: String(value.revision), path, content: value.content };
}
export interface Action { path: string; body: Record<string, string>; key: string }
export async function act(action: Action, signal: AbortSignal): Promise<void> {
  const value = await post(action.path, action.body, signal, action.key);
  if (!object(value) || typeof value.exerciseId !== 'string' || typeof value.state !== 'string') return invalid();
}
export function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.code === 'PASSWORD_CHANGE_REQUIRED') return '登录已失效或需要改密，请前往账号页面。';
    if (error.code === 'ACTIVE_EXERCISE_EXISTS') return '已有未结束演练，请刷新查看。';
    if (error.status === 409) return '状态已变化，请刷新核对；不要自动创建新的请求。';
  }
  return '无法确认结果。请检查服务后刷新查询，或使用原请求重试。';
}
