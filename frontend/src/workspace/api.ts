import { ApiError, object, post, request } from '../api/request';

export interface Problem {
  problemVersionId: string; title: string; version: string; requirements: string;
  durationSeconds: number; editableRoots: string[]; acceptanceFiles: Record<string, string>; executionAvailable?: boolean;
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
      || (value.executionAvailable !== undefined && typeof value.executionAvailable !== 'boolean')
      || !strings(value.editableRoots) || !object(value.acceptanceFiles)) return invalid();
    const acceptanceFiles: Record<string, string> = {};
    for (const [path, content] of Object.entries(value.acceptanceFiles)) {
      if (typeof content !== 'string') return invalid();
      acceptanceFiles[path] = content;
    }
    return { problemVersionId: value.problemVersionId, title: value.title, version: value.version, requirements: value.requirements,
      durationSeconds: value.durationSeconds, editableRoots: value.editableRoots, acceptanceFiles, executionAvailable: value.executionAvailable === true };
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
export async function act(action: Action, signal: AbortSignal, credential?: string): Promise<void> {
  const value = await post(action.path, action.body, signal, action.key, credential);
  if (!object(value) || typeof value.exerciseId !== 'string' || typeof value.state !== 'string') return invalid();
}
export interface Control { exerciseId: string; operationId: string | null; state: string; generation: string; held: boolean; credential: string | null }
export async function control(exercise: string, kind: 'acquire' | 'takeover' | 'release' | null, signal: AbortSignal, key?: string, credential?: string): Promise<Control> {
  const path = `/api/exercises/${encodeURIComponent(exercise)}/control`;
  const value = kind ? await post(`${path}/${kind}`, {}, signal, key, credential) : await request(path, signal);
  if (!object(value) || value.exerciseId !== exercise || !['SUCCEEDED', 'PENDING'].includes(String(value.state))
    || typeof value.generation !== 'string' || !/^\d+$/.test(value.generation) || typeof value.held !== 'boolean'
    || (value.operationId !== null && typeof value.operationId !== 'string')
    || (value.credential !== null && (typeof value.credential !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.credential)))) return invalid();
  return { exerciseId: exercise, operationId: value.operationId, state: String(value.state), generation: value.generation, held: value.held, credential: value.credential };
}
export interface FileChange { path: string; kind: 'CREATE' | 'UPDATE' | 'DELETE'; content: string }
export interface Mutation { operationId: string; exerciseId: string; state: string; revision: string | null; changed: boolean | null; failureCode: string | null }
function mutation(value: unknown): Mutation {
  if (!object(value) || typeof value.operationId !== 'string' || typeof value.exerciseId !== 'string'
    || !['PENDING', 'PENDING_RECONCILIATION', 'SUCCEEDED', 'REJECTED'].includes(String(value.state))
    || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || (value.changed !== null && typeof value.changed !== 'boolean')
    || (value.failureCode !== null && typeof value.failureCode !== 'string')) return invalid();
  return { operationId: value.operationId, exerciseId: value.exerciseId, state: String(value.state), revision: value.revision,
    changed: value.changed, failureCode: value.failureCode };
}
export async function save(exercise: string, revision: string, changes: FileChange[], key: string, credential: string, signal: AbortSignal): Promise<Mutation> {
  return mutation(await post(`/api/exercises/${encodeURIComponent(exercise)}/file-batches`, { expectedRevision: revision, changes }, signal, key, credential));
}
export async function operation(id: string, signal: AbortSignal): Promise<Mutation> { return mutation(await request(`/api/operations/${encodeURIComponent(id)}`, signal)); }
export function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.code === 'PASSWORD_CHANGE_REQUIRED') return '登录已失效或需要改密，请前往账号页面。';
    if (error.code === 'REVISION_CONFLICT') return '工作区版本已变化。草稿已保留，请比较服务端内容后再保存。';
    if (error.code === 'STALE_CONTROL' || error.code === 'CONTROL_HELD') return '当前页面没有操作权，请显式接管；草稿不会自动清除。';
    if (error.code === 'EXERCISE_EXPIRED') return '作答已到期，本地内容不会自动提交。';
    if (error.code === 'TEST_TARGET_UNAVAILABLE') return '这个固定题目版本尚未配备正式执行配置，不会替换已有工作区版本，请联系维护者。';
    if (error.code === 'TEST_SLOT_BUSY') return '已有测试占用执行名额，不会排队。请查询原任务，确认结束后再运行。';
    if (error.code === 'WORKSPACE_CAPACITY') return '工作区容量不足，本批次未保存，草稿已保留。';
    if (error.code === 'WRITE_NOT_COMMITTED') return '已核对原操作未提交。请检查当前版本后显式重新保存。';
    if (error.code === 'FILE_EXISTS') return '目标文件已存在，本批次未应用。';
    if (error.code === 'ACTIVE_EXERCISE_EXISTS') return '已有未结束演练，请刷新查看。';
    if (error.status === 409) return '状态已变化，请刷新核对；不要自动创建新的请求。';
  }
  return '无法确认结果。请检查服务后刷新查询，或使用原请求重试。';
}
