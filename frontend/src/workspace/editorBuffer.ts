import type { WorkspaceFile } from './api';

export interface Buffer {
  path: string; content: string; baseContent: string; revision: string; remote: WorkspaceFile | null;
}
export function buffer(file: WorkspaceFile): Buffer {
  return { path: file.path, content: file.content, baseContent: file.content, revision: file.revision, remote: null };
}
export function dirty(value: Buffer): boolean { return value.content !== value.baseContent; }
export function observe(value: Buffer, file: WorkspaceFile): void {
  if (file.path !== value.path) throw new Error('File identity mismatch');
  if (file.revision === value.revision) return;
  if (dirty(value)) value.remote = file;
  else Object.assign(value, buffer(file));
}
export function committed(value: Buffer, submitted: string, revision: string): void {
  // Never replace content: it may have changed while the request was in flight.
  value.baseContent = submitted;
  value.revision = revision;
  if (value.remote && BigInt(value.remote.revision) <= BigInt(revision)) value.remote = null;
}
export function useLatestBaseline(value: Buffer): void {
  if (!value.remote) return;
  value.baseContent = value.remote.content;
  value.revision = value.remote.revision;
  value.remote = null;
}
