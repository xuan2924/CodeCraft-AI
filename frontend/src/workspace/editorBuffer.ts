import type { WorkspaceFile } from './api';

export interface Buffer {
  path: string; content: string; baseContent: string; baseContentSha256?: string; revision: string; remote: WorkspaceFile | null;
}
export function buffer(file: WorkspaceFile): Buffer {
  return {
    path: file.path,
    content: file.content,
    baseContent: file.content,
    ...(file.contentSha256 ? { baseContentSha256: file.contentSha256 } : {}),
    revision: file.revision,
    remote: null,
  };
}
export function dirty(value: Buffer): boolean { return value.content !== value.baseContent; }
export function observe(value: Buffer, file: WorkspaceFile): void {
  if (file.path !== value.path) throw new Error('File identity mismatch');
  if (file.revision === value.revision) return;
  if (dirty(value)) {
    const unchanged = file.contentSha256 && value.baseContentSha256
      ? file.contentSha256 === value.baseContentSha256
      : file.content === value.baseContent;
    if (unchanged) {
      value.revision = file.revision;
      if (file.contentSha256) value.baseContentSha256 = file.contentSha256;
      value.remote = null;
      return;
    }
    value.remote = file;
  } else {
    Object.assign(value, buffer(file));
  }
}
export function committed(value: Buffer, submitted: string, revision: string, contentSha256?: string): void {
  // Never replace content: it may have changed while the request was in flight.
  value.baseContent = submitted;
  value.baseContentSha256 = contentSha256;
  value.revision = revision;
  if (value.remote && BigInt(value.remote.revision) <= BigInt(revision)) value.remote = null;
}
export function useLatestBaseline(value: Buffer): void {
  if (!value.remote) return;
  value.baseContent = value.remote.content;
  value.baseContentSha256 = value.remote.contentSha256;
  value.revision = value.remote.revision;
  value.remote = null;
}
