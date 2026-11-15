import { ApiError, object, ownerHeaders, post, put, remove, request, submitJson } from '../api/request';

export interface SkillFile { path: string; content: string }
export interface SkillPackage { formatVersion: 1; files: SkillFile[] }
export interface Skill { id: string; name: string; description: string; version: string; contentVersion: string; enabled: boolean; deleted: boolean; contentBytes: number }
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function uuid(value: unknown): value is string { return typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value); }
export function parseSkill(value: unknown): Skill {
  if (!object(value) || !uuid(value.id) || !uuid(value.contentVersion) || typeof value.name !== 'string' || value.name.length > 64
    || typeof value.description !== 'string' || value.description.length > 2048 || typeof value.version !== 'string' || !/^[1-9]\d{0,18}$/.test(value.version)
    || typeof value.enabled !== 'boolean' || typeof value.deleted !== 'boolean' || typeof value.contentBytes !== 'number'
    || !Number.isSafeInteger(value.contentBytes) || value.contentBytes < 1) return invalid();
  return { id: value.id, name: value.name, description: value.description, version: value.version, contentVersion: value.contentVersion,
    enabled: value.enabled, deleted: value.deleted, contentBytes: value.contentBytes };
}
export function parsePackage(value: unknown): SkillPackage {
  if (!object(value) || value.formatVersion !== 1 || !Array.isArray(value.files) || !value.files.length || value.files.length > 256
    || Object.keys(value).some(key => !['formatVersion', 'files'].includes(key))) return invalid();
  let bytes = 0;
  const files = value.files.map((file): SkillFile => {
    if (!object(file) || typeof file.path !== 'string' || file.path.length > 1024 || typeof file.content !== 'string'
      || Object.keys(file).some(key => !['path', 'content'].includes(key))) return invalid();
    bytes += new TextEncoder().encode(file.content).length;
    if (bytes > 1048576) return invalid();
    return { path: file.path, content: file.content };
  });
  return { formatVersion: 1, files };
}
export async function listSkills(owner: string, signal: AbortSignal): Promise<Skill[]> {
  const result = await request('/api/skills', signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(result) || result.length > 1000) return invalid();
  return result.map(parseSkill);
}
export async function skillPackage(owner: string, id: string, signal: AbortSignal): Promise<SkillPackage> {
  return parsePackage(await request(`/api/skills/${encodeURIComponent(id)}/package`, signal, { headers: ownerHeaders(owner) }));
}
export async function saveSkill(owner: string, current: Skill | null, body: SkillPackage, key: string, signal: AbortSignal): Promise<Skill> {
  return parseSkill(await (current ? put(`/api/skills/${current.id}?expectedVersion=${current.version}`, body, signal, key, owner)
    : post('/api/skills', body, signal, key, undefined, owner)));
}
export async function importSkill(owner: string, rawJson: string, key: string, signal: AbortSignal): Promise<Skill> {
  if (new TextEncoder().encode(rawJson).length > 2097152) throw new ApiError(422, 'SKILL_LIMIT_EXCEEDED');
  return parseSkill(await submitJson('POST', '/api/skills', rawJson, signal, key, owner));
}
export async function changeSkill(owner: string, skill: Skill, deleting: boolean, key: string, signal: AbortSignal): Promise<Skill> {
  const path = `/api/skills/${skill.id}`;
  return parseSkill(await (deleting ? remove(`${path}?expectedVersion=${skill.version}`, signal, key, undefined, owner)
    : put(`${path}/enabled?expectedVersion=${skill.version}&enabled=${!skill.enabled}`, null, signal, key, owner)));
}
export function skillError(error: unknown): string {
  if (!(error instanceof ApiError)) return '无法确认操作结果，请重新读取列表后再操作。';
  const messages: Record<string, string> = {
    SKILL_INVALID_PACKAGE: '文本包格式无效，请检查元数据、路径、编码和文件类型。',
    SKILL_LIMIT_EXCEEDED: '超过 Skill 额度，整次操作已拒绝，原版本不变。',
    SKILL_VERSION_CONFLICT: '原件已被其他页面修改，请重新打开最新版本。',
    SKILL_NAME_CONFLICT: '同名 Skill 已存在，停用项也占用名称。',
    SKILL_NOT_FOUND: 'Skill 已删除或不可访问。',
    SKILL_IDEMPOTENCY_CONFLICT: '请求身份与内容冲突，请重新读取后再操作。',
  };
  return messages[error.code] ?? '无法确认操作结果，请重新读取列表后再操作。';
}
