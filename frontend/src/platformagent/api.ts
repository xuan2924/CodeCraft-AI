import { ApiError, object, ownerHeaders, post, request } from '../api/request';
import { connection, model, type Connection, type Model, type ConnectionInput, type ModelInput } from '../modelaccess/api';
import { parsePackage, type SkillPackage } from '../skills/api';
export type Role = 'INTERVIEWER' | 'EVALUATOR';
export interface Version { id: string; role: Role; state: 'DRAFT' | 'PUBLISHED' | 'RETIRED'; revision: string; credentialRevision: string;
  credentialAvailable: boolean; createdAt: string; publishedAt: string | null;
  definition: { connection: Connection; model: Model; instructions: string; skills: SkillPackage[]; policyVersion: string } }
export interface RolePointer { role: Role; currentVersion: string | null; revision: string }
export interface Catalog { versions: Version[]; roles: RolePointer[] }
export interface Check { id: string; versionId: string; credentialRevision: string; state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'; createdAt: string; finishedAt: string | null; errorCode: string | null }
export interface Draft { role: Role; connection: ConnectionInput; model: ModelInput; instructions: string; skills: SkillPackage[] }
export interface Action { expectedRevision: string; expectedRoleRevision?: string; feeAcknowledged?: boolean; apiKey?: string }
const root = '/api/admin/platform-agents';
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function text(value: unknown, max = 65536): string { if (typeof value !== 'string' || value.length > max) return invalid(); return value; }
function id(value: unknown): string { const result = text(value, 36); if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(result)) return invalid(); return result; }
function revision(value: unknown): string { const result = text(value, 19); if (!/^\d{1,19}$/.test(result)) return invalid(); return result; }
function role(value: unknown): Role { if (value !== 'INTERVIEWER' && value !== 'EVALUATOR') return invalid(); return value; }
function timestamp(value: unknown): string { const result = text(value, 64); if (!Number.isFinite(Date.parse(result))) return invalid(); return result; }
export function parseVersion(value: unknown): Version {
  if (!object(value) || !object(value.definition) || !['DRAFT', 'PUBLISHED', 'RETIRED'].includes(String(value.state))
    || typeof value.credentialAvailable !== 'boolean' || !Array.isArray(value.definition.skills) || value.definition.skills.length > 10) return invalid();
  const state = value.state;
  if (state !== 'DRAFT' && state !== 'PUBLISHED' && state !== 'RETIRED') return invalid();
  return { id: id(value.id), role: role(value.role), state, revision: revision(value.revision), credentialRevision: revision(value.credentialRevision),
    credentialAvailable: value.credentialAvailable, createdAt: timestamp(value.createdAt), publishedAt: value.publishedAt === null ? null : timestamp(value.publishedAt),
    definition: { connection: connection(value.definition.connection), model: model(value.definition.model), instructions: text(value.definition.instructions), policyVersion: text(value.definition.policyVersion, 64),
      skills: value.definition.skills.map((skill: unknown) => { if (!object(skill)) return invalid(); return parsePackage({ formatVersion: 1, files: skill.files }); }) } };
}
function parseCheck(value: unknown): Check {
  if (!object(value)) return invalid();
  const state = value.state;
  if (state !== 'QUEUED' && state !== 'RUNNING' && state !== 'SUCCEEDED' && state !== 'FAILED') return invalid();
  return { id: id(value.id), versionId: id(value.versionId), credentialRevision: revision(value.credentialRevision), state,
    createdAt: timestamp(value.createdAt), finishedAt: value.finishedAt === null ? null : timestamp(value.finishedAt), errorCode: value.errorCode === null ? null : text(value.errorCode, 128) };
}
export async function list(owner: string, signal: AbortSignal, after?: string): Promise<Catalog> {
  const value = await request(root + (after ? `?after=${encodeURIComponent(after)}` : ''), signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.versions) || value.versions.length > 50 || !Array.isArray(value.roles) || value.roles.length !== 2) return invalid();
  return { versions: value.versions.map(parseVersion), roles: value.roles.map((entry: unknown) => {
    if (!object(entry)) return invalid(); return { role: role(entry.role), currentVersion: entry.currentVersion === null ? null : id(entry.currentVersion), revision: revision(entry.revision) };
  }) };
}
export async function get(owner: string, version: string, signal: AbortSignal): Promise<Version> {
  const value = parseVersion(await request(`${root}/${encodeURIComponent(version)}`, signal, { headers: ownerHeaders(owner) }));
  if (value.id !== version) return invalid(); return value;
}
export async function create(owner: string, input: Draft, key: string, signal: AbortSignal): Promise<Version> {
  return parseVersion(await post(root, input, signal, key, undefined, owner));
}
export async function change(owner: string, version: string, action: 'publish' | 'retire' | 'credential', input: Action, key: string, signal: AbortSignal): Promise<Version> {
  const value = parseVersion(await post(`${root}/${encodeURIComponent(version)}/${action}`, input, signal, key, undefined, owner));
  if (value.id !== version) return invalid(); return value;
}
export async function check(owner: string, version: string, input: Action, key: string, signal: AbortSignal): Promise<Check> {
  const value = parseCheck(await post(`${root}/${encodeURIComponent(version)}/checks`, input, signal, key, undefined, owner));
  if (value.versionId !== version) return invalid(); return value;
}
export async function latest(owner: string, version: string, signal: AbortSignal): Promise<Check | null> {
  try {
    const value = parseCheck(await request(`${root}/${encodeURIComponent(version)}/checks/latest`, signal, { headers: ownerHeaders(owner) }));
    if (value.versionId !== version) return invalid(); return value;
  }
  catch (error) { if (error instanceof ApiError && error.code === 'PLATFORM_CHECK_NOT_FOUND') return null; throw error; }
}
export function message(error: unknown): string {
  if (!(error instanceof ApiError)) return '请求结果未知，请核对并重试原操作。';
  const messages: Record<string, string> = { PLATFORM_VERSION_CONFLICT: '配置或角色版本已变化，请刷新后重新确认。', PLATFORM_CHECK_REQUIRED: '发布需要当前凭证修订的有效检查，请显式重新检查。',
    PLATFORM_CONFIGURATION_UNAVAILABLE: '平台角色尚不可用，请完成配置、检查和发布。', PLATFORM_CHECK_RATE_LIMITED: '检查次数达到窗口限额，请稍后显式重试。',
    PLATFORM_CHECK_ACTIVE: '该管理员已有检查在途，请先等待结果。', PLATFORM_CHECK_FEE_REQUIRED: '请先确认平台检查可能产生费用。',
    MODEL_ENCRYPTION_UNAVAILABLE: '部署尚未配置模型凭证加密密钥，请联系维护者。' };
  if ([401, 403].includes(error.status)) return '需要有效管理员身份，请重新确认登录。';
  return messages[error.code] ?? `操作未完成（${error.code}），请检查参数或配置状态。`;
}
