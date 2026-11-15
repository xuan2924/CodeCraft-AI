import { ApiError, object, ownerHeaders, request } from '../api/request';
import { parsePackage } from '../skills/api';
export interface SkillSnapshot { skillId: string; versionId: string; name: string; description: string; explicitlySelected: boolean; explicitLoaded: boolean }
function uuid(value: unknown): value is string { return typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value); }
export function parseSnapshot(value: unknown): SkillSnapshot {
  if (!object(value) || !uuid(value.skillId) || !uuid(value.versionId) || typeof value.name !== 'string' || value.name.length > 64
    || typeof value.description !== 'string' || value.description.length > 2048 || typeof value.explicitlySelected !== 'boolean'
    || typeof value.explicitLoaded !== 'boolean' || value.explicitLoaded && !value.explicitlySelected) throw new ApiError(502, 'INVALID_RESPONSE');
  return { skillId: value.skillId, versionId: value.versionId, name: value.name, description: value.description,
    explicitlySelected: value.explicitlySelected, explicitLoaded: value.explicitLoaded };
}
export async function taskSkills(owner: string, task: string, signal: AbortSignal): Promise<SkillSnapshot[]> {
  const result = await request(`/api/copilot-tasks/${encodeURIComponent(task)}/skills`, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(result) || result.length > 1000) throw new ApiError(502, 'INVALID_RESPONSE');
  return result.map(parseSnapshot);
}
export async function snapshotPackage(owner: string, task: string, skill: string, signal: AbortSignal) {
  return parsePackage(await request(`/api/copilot-tasks/${encodeURIComponent(task)}/skills/${encodeURIComponent(skill)}/package`, signal, { headers: ownerHeaders(owner) }));
}
