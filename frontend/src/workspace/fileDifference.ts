import { ApiError, object } from '../api/request';

export interface FileDifference { kind: string; path: string; previousContent: string; content: string }
export function managedPath(path: string): boolean {
  return path.length <= 4096 && /^(src\/main\/java|src\/test\/java)\/[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*$/.test(path)
    && !path.split('/').some(part => part === '.' || part === '..');
}
export function parseFileDifferences(value: unknown, managedOnly = false): FileDifference[] {
  const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
  if (!Array.isArray(value) || value.length > 400) return invalid();
  const paths = new Set<string>();
  let beforeBytes = 0; let afterBytes = 0;
  const encoder = new TextEncoder();
  return value.map((entry: unknown) => {
    if (!object(entry) || typeof entry.kind !== 'string' || !['CREATE', 'UPDATE', 'DELETE'].includes(entry.kind)
      || typeof entry.path !== 'string' || entry.path.length > 4096 || /[\\\0]/.test(entry.path)
      || entry.path.split('/').some(part => !part || part === '.' || part === '..') || paths.has(entry.path)
      || (managedOnly && !managedPath(entry.path)) || typeof entry.previousContent !== 'string' || typeof entry.content !== 'string'
      || entry.previousContent.length > 1048576 || entry.content.length > 1048576 || /\0/.test(entry.previousContent + entry.content)
      || (entry.kind === 'CREATE' && entry.previousContent !== '') || (entry.kind === 'DELETE' && entry.content !== '')
      || (entry.kind === 'UPDATE' && entry.previousContent === entry.content)) return invalid();
    const before = encoder.encode(entry.previousContent).length; const after = encoder.encode(entry.content).length;
    beforeBytes += before; afterBytes += after;
    if (before > 1048576 || after > 1048576 || beforeBytes > 10 * 1048576 || afterBytes > 10 * 1048576) return invalid();
    paths.add(entry.path);
    return { kind: entry.kind, path: entry.path, previousContent: entry.previousContent, content: entry.content };
  });
}
