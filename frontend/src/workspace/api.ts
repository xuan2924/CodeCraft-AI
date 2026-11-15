import { ApiError, object, post, request } from '../api/request';

export interface Problem {
  problemVersionId: string; title: string; version: string; requirements: string;
  durationSeconds: number; editableRoots: string[]; acceptanceFiles: Record<string, string>; executionAvailable?: boolean;
}
export interface Exercise {
  exerciseId: string; problemVersionId: string; workspaceInstanceId: string; state: string;
  revision: string | null; paths: string[]; deadlineAt: string | null;
  preparationFailureReason: string | null; cleanupState: string | null;
  finalization?: Finalization | null;
}
export interface Finalization {
  operationId: string; finalRevision: string | null; verification: string; resultTaskId: string | null;
  attempts: number; maxAttempts: number; deadlineAt: string | null; endedAt: string | null; status: string;
}
function finalization(value: unknown): Finalization | null {
  if (value === undefined || value === null) return null;
  if (!object(value) || typeof value.operationId !== 'string' || !['PENDING', 'PASSED', 'FAILED', 'TIMED_OUT', 'UNAVAILABLE'].includes(String(value.verification))
    || (value.finalRevision !== null && (typeof value.finalRevision !== 'string' || !/^\d+$/.test(value.finalRevision)))
    || (value.resultTaskId !== null && typeof value.resultTaskId !== 'string')
    || typeof value.attempts !== 'number' || !Number.isInteger(value.attempts) || value.attempts < 0
    || typeof value.maxAttempts !== 'number' || !Number.isInteger(value.maxAttempts) || value.maxAttempts < 1 || value.maxAttempts > 3 || value.attempts > value.maxAttempts
    || !['RECONCILIATION_REQUIRED', 'FINAL_TESTING', 'ENDED'].includes(String(value.status))) return invalid();
  for (const timestamp of [value.deadlineAt, value.endedAt]) {
    if (timestamp !== null && (typeof timestamp !== 'string' || !Number.isFinite(Date.parse(timestamp)))) return invalid();
  }
  return { operationId: value.operationId, finalRevision: value.finalRevision, verification: String(value.verification), resultTaskId: value.resultTaskId,
    attempts: value.attempts, maxAttempts: value.maxAttempts, deadlineAt: value.deadlineAt === null ? null : String(value.deadlineAt),
    endedAt: value.endedAt === null ? null : String(value.endedAt), status: String(value.status) };
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
export async function current(signal: AbortSignal, selectedExercise?: string): Promise<Exercise | null> {
  let value: unknown;
  try { value = await request(selectedExercise ? `/api/exercises/${encodeURIComponent(selectedExercise)}` : '/api/exercises/current', signal); }
  catch (error) {
    if (!(error instanceof ApiError && error.status === 404) || selectedExercise) throw error;
    return null;
  }
  if (!object(value) || typeof value.exerciseId !== 'string' || typeof value.problemVersionId !== 'string'
    || typeof value.workspaceInstanceId !== 'string' || typeof value.state !== 'string'
    || !['PREPARING', 'READY', 'PREPARATION_FAILED', 'ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED'].includes(value.state)
    || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || (value.preparationFailureReason !== null && typeof value.preparationFailureReason !== 'string')
    || (value.cleanupState !== null && !['PENDING', 'SUCCEEDED', 'FAILED'].includes(String(value.cleanupState)))
    || !strings(value.paths) || (value.deadlineAt !== null && (typeof value.deadlineAt !== 'string' || !Number.isFinite(Date.parse(value.deadlineAt))))) return invalid();
  return { exerciseId: value.exerciseId, problemVersionId: value.problemVersionId, workspaceInstanceId: value.workspaceInstanceId,
    state: value.state, revision: value.revision, paths: value.paths, deadlineAt: value.deadlineAt,
    preparationFailureReason: value.preparationFailureReason, cleanupState: value.cleanupState === null ? null : String(value.cleanupState),
    finalization: finalization(value.finalization) };
}
export async function file(exercise: Exercise, path: string, signal: AbortSignal): Promise<WorkspaceFile> {
  const value = await request(`/api/exercises/${encodeURIComponent(exercise.exerciseId)}/files?path=${encodeURIComponent(path)}`, signal);
  if (!object(value) || value.workspaceInstanceId !== exercise.workspaceInstanceId || value.path !== path
    || value.revision !== exercise.revision || typeof value.content !== 'string') return invalid();
  return { workspaceInstanceId: exercise.workspaceInstanceId, revision: String(value.revision), path, content: value.content };
}
export interface Action { path: string; body: Record<string, string>; key: string }
export async function act(action: Action, signal: AbortSignal, credential?: string): Promise<{ exerciseId: string; state: string }> {
  const value = await post(action.path, action.body, signal, action.key, credential);
  if (!object(value) || typeof value.exerciseId !== 'string' || typeof value.state !== 'string') return invalid();
  return { exerciseId: value.exerciseId, state: value.state };
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
export function parseMutation(value: unknown): Mutation {
  if (!object(value) || typeof value.operationId !== 'string' || typeof value.exerciseId !== 'string'
    || !['PENDING', 'PENDING_RECONCILIATION', 'SUCCEEDED', 'REJECTED'].includes(String(value.state))
    || (value.revision !== null && (typeof value.revision !== 'string' || !/^\d+$/.test(value.revision)))
    || (value.changed !== null && typeof value.changed !== 'boolean')
    || (value.failureCode !== null && typeof value.failureCode !== 'string')) return invalid();
  return { operationId: value.operationId, exerciseId: value.exerciseId, state: String(value.state), revision: value.revision,
    changed: value.changed, failureCode: value.failureCode };
}
export async function save(exercise: string, revision: string, changes: FileChange[], key: string, credential: string, signal: AbortSignal): Promise<Mutation> {
  return parseMutation(await post(`/api/exercises/${encodeURIComponent(exercise)}/file-batches`, { expectedRevision: revision, changes }, signal, key, credential));
}
export async function operation(id: string, signal: AbortSignal): Promise<Mutation> { return parseMutation(await request(`/api/operations/${encodeURIComponent(id)}`, signal)); }
export function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.code === 'PASSWORD_CHANGE_REQUIRED') return '登录已失效或需要改密，请前往账号页面。';
    if (error.code === 'MODEL_SELECTION_REQUIRED') return '请先显式选择本次演练使用的模型。';
    if (error.code === 'MODEL_CHECK_REQUIRED') return '所选版本的基础连接与工具闭环检查尚未通过或已过期，请确认费用后重新检查；尚未开始计时。';
    if (error.code === 'MODEL_VERSION_CONFLICT') return '所选配置已更新，请显式应用更新版本并在开始计时前完成检查。';
    if (error.code === 'MODEL_CONNECTION_NOT_FOUND' || error.code === 'MODEL_CONFIGURATION_NOT_FOUND') return '所选配置已删除，请重新选择。';
    if (error.code === 'REVISION_CONFLICT') return '工作区版本已变化。草稿已保留，请比较服务端内容后再保存。';
    if (error.code === 'STALE_CONTROL' || error.code === 'CONTROL_HELD') return '当前页面没有操作权，请显式接管；草稿不会自动清除。';
    if (error.code === 'EXERCISE_EXPIRED') return '作答已到期，本地内容不会自动提交。';
    if (error.code === 'TEST_TARGET_UNAVAILABLE') return '这个固定题目版本尚未配备正式执行配置，不会替换已有工作区版本，请联系维护者。';
    if (error.code === 'TEST_SLOT_BUSY' || error.code === 'COMMAND_SLOT_BUSY') return '已有命令或测试占用执行名额，不会排队。请查询原执行，确认结束后再运行。';
    if (error.code === 'CODE_HISTORY_UNAVAILABLE') return '此版本未被历史保留或已清理，不能预览或恢复；当前代码和草稿不受影响。';
    if (error.code === 'RESTORE_CONFLICT') return '选中修改的文件已有后续变更，不能安全撤销；不会自动合并或覆盖。';
    if (error.code === 'RESTORE_PREVIEW_CHANGED') return '恢复预览已变化，请重新预览并确认；本次没有应用恢复。';
    if (error.code === 'RESTORE_BUSY') return '仍有写入或命令未收敛，请先核对原操作；不会抢先恢复代码。';
    if (error.code === 'COMMAND_UNAVAILABLE') return '这个题目版本尚未配备开发命令执行配置。';
    if (error.code === 'COMMAND_HISTORY_PRUNED') return '这条命令的输出记录已清理，不能重新读取；不会因此重放命令。';
    if (error.code === 'COMMAND_OUTPUT_UNAVAILABLE') return '候选文件无法完整读取或不符合受管理文本文件规则；文件同步没有被宣称成功，请保留原执行身份。';
    if (error.code === 'STORAGE_BUDGET_EXCEEDED') return '单场数据容量不足，新增操作未受理，草稿已保留；仍可恢复停止、保留草稿结束或删除已结束演练。';
    if (error.code === 'STORAGE_METER_UNAVAILABLE') return '暂时无法核对数据容量，不会按零占用受理；草稿已保留。';
    if (error.code === 'REQUEST_TOO_LARGE') return '请求内容过大，本次未受理；请减少本批次改动，草稿已保留。';
    if (error.code === 'REQUEST_CAPACITY') return '大文本处理繁忙，本次未受理，草稿已保留；请稍后重试。';
    if (error.code === 'DELETION_RETRY_THROTTLED') return '清理正在执行或距上次手动推进不足一分钟；后台会继续重试，不会创建并行清理。';
    if (error.code === 'WORKSPACE_CAPACITY') return '工作区容量不足，本批次未保存，草稿已保留。';
    if (error.code === 'WRITE_NOT_COMMITTED') return '已核对原操作未提交。请检查当前版本后显式重新保存。';
    if (error.code === 'FILE_EXISTS') return '目标文件已存在，本批次未应用。';
    if (error.code === 'EXERCISE_DELETED') return '演练已删除，不能恢复作答。';
    if (error.code === 'TEST_HISTORY_PRUNED') return '该普通测试已移出完整历史；请查看最近记录。演练代码和草稿不受影响。';
    if (error.code === 'HISTORY_LIMIT_REACHED') return '已达到历史演练数量上限，请先删除旧演练再新建。';
    if (error.code === 'ACTIVE_EXERCISE_EXISTS') return '已有未结束演练，请刷新查看。';
    if (error.status === 409) return '状态已变化，请刷新核对；不要自动创建新的请求。';
  }
  return '无法确认结果。请检查服务后刷新查询，或使用原请求重试。';
}
