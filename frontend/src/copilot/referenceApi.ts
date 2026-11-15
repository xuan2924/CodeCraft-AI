import { ApiError, object, ownerHeaders, request } from '../api/request';
export type ReferenceKind = 'FILE' | 'CODE' | 'TEST_LOG' | 'COMMAND_LOG';
export interface ReferenceSelection { kind: ReferenceKind; source: string; version: string; offset: number; limit: number; fingerprint: string }
export interface ReferencePage extends ReferenceSelection { text: string; nextOffset: number | null; retainedBytes: number; sourceIncomplete: boolean; droppedBytes: string }
export interface ReferenceSource { id: string; label: string; revision: string; state: string }
export async function sources(owner: string, exercise: string, conversation: string, kind: ReferenceKind, after: string | null, signal: AbortSignal): Promise<{ sources: ReferenceSource[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ kind, ...(after === null ? {} : { after }) });
  const value = await request(`/api/exercises/${encodeURIComponent(exercise)}/copilot/conversations/${encodeURIComponent(conversation)}/references/sources?${params}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || !Array.isArray(value.sources) || value.sources.length > 100 || value.nextCursor !== null && (typeof value.nextCursor !== 'string' || value.nextCursor.length > 512)) return invalid();
  const entries = value.sources.map((item: unknown): ReferenceSource => {
    if (!object(item) || typeof item.id !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(item.id)
      || typeof item.label !== 'string' || item.label.length > 512 || typeof item.revision !== 'string' || !/^\d{1,19}$/.test(item.revision)
      || typeof item.state !== 'string' || !/^[A-Z_]{1,64}$/.test(item.state)) return invalid();
    return { id: item.id, label: item.label, revision: item.revision, state: item.state };
  });
  if (new Set(entries.map(item => item.id)).size !== entries.length) return invalid();
  return { sources: entries, nextCursor: value.nextCursor };
}
function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
export function selection(page: ReferencePage): ReferenceSelection { return { kind: page.kind, source: page.source, version: page.version, offset: page.offset, limit: page.limit, fingerprint: page.fingerprint }; }
export async function preview(owner: string, exercise: string, conversation: string, kind: ReferenceKind, source: string, offset: number, limit: number, version: string | null, signal: AbortSignal): Promise<ReferencePage> {
  const params = new URLSearchParams({ kind, source, offset: String(offset), limit: String(limit), ...(version === null ? {} : { version }) });
  const value = await request(`/api/exercises/${encodeURIComponent(exercise)}/copilot/conversations/${encodeURIComponent(conversation)}/references?${params}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || value.kind !== kind || value.source !== source || value.offset !== offset || value.limit !== limit
    || typeof value.version !== 'string' || value.version.length > 256 || version !== null && value.version !== version
    || typeof value.fingerprint !== 'string' || !/^[a-zA-Z0-9_-]{32,128}$/.test(value.fingerprint)
    || typeof value.text !== 'string' || typeof value.sourceIncomplete !== 'boolean'
    || typeof value.retainedBytes !== 'number' || !Number.isSafeInteger(value.retainedBytes) || value.retainedBytes < 0 || value.retainedBytes > 8388608
    || typeof value.droppedBytes !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value.droppedBytes) || BigInt(value.droppedBytes) > 9223372036854775807n) return invalid();
  const end = offset + new TextEncoder().encode(value.text).length;
  if (end > value.retainedBytes || end - offset > limit || value.nextOffset !== null && (value.nextOffset !== end || end >= value.retainedBytes || end === offset)
    || value.nextOffset === null && end !== value.retainedBytes) return invalid();
  return { kind, source, version: value.version, offset, limit, fingerprint: value.fingerprint, text: value.text, nextOffset: value.nextOffset === null ? null : end,
    retainedBytes: value.retainedBytes, sourceIncomplete: value.sourceIncomplete, droppedBytes: value.droppedBytes };
}
