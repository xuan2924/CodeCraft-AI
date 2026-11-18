export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export async function request(path: string, signal: AbortSignal, options: RequestInit = {}): Promise<unknown> {
  const response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store',
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]) });
  if (response.status === 204) return null;
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, object(body) && typeof body.code === 'string' ? body.code : 'REQUEST_FAILED');
  if (!object(body) || body.code !== 'OK' || !('data' in body)) throw new ApiError(502, 'INVALID_RESPONSE');
  return body.data;
}
/** Private binary delivery uses the same session/owner boundary and never persists a public URL. */
export async function download(path: string, signal: AbortSignal, owner: string, mediaType: string): Promise<Blob> {
  const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', headers: ownerHeaders(owner),
    signal: AbortSignal.any([signal, AbortSignal.timeout(30000)]) });
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null);
    throw new ApiError(response.status, object(body) && typeof body.code === 'string' ? body.code : 'REQUEST_FAILED');
  }
  if (response.headers.get('Content-Type')?.split(';')[0] !== mediaType) throw new ApiError(502, 'INVALID_RESPONSE');
  return response.blob();
}
/** Same-origin read-only transport; callers own protocol validation and recovery. */
export function events(path: string, types: readonly string[], receive: (type: string, data: string, id: string) => void,
  failed: () => void, signal: AbortSignal): () => void {
  if (!path.startsWith('/api/') || signal.aborted) return () => {};
  const source = new EventSource(path);
  let closed = false;
  const close = () => { if (!closed) { closed = true; source.close(); signal.removeEventListener('abort', close); } };
  for (const type of types) source.addEventListener(type, (event) => {
    if (closed) return;
    if (!(event instanceof MessageEvent) || typeof event.data !== 'string') { close(); failed(); return; }
    receive(type, event.data, event.lastEventId);
  });
  source.addEventListener('error', () => { if (!closed) { close(); failed(); } });
  signal.addEventListener('abort', close, { once: true });
  return close;
}
export function ownerHeaders(owner?: string): Record<string, string> { return owner === undefined ? {} : { 'X-Codecraft-Owner': owner }; }
export async function post(path: string, body: unknown, signal: AbortSignal, key?: string, operationCredential?: string, owner?: string): Promise<unknown> {
  return mutate('POST', path, body, signal, key, operationCredential, owner);
}
export async function put(path: string, body: unknown, signal: AbortSignal, key: string, owner?: string): Promise<unknown> {
  return mutate('PUT', path, body, signal, key, undefined, owner);
}
export async function remove(path: string, signal: AbortSignal, key: string, body?: unknown, owner?: string): Promise<unknown> {
  return mutate('DELETE', path, body, signal, key, undefined, owner);
}
/** Preserve imported JSON bytes so the server can reject duplicate fields instead of silently overwriting them. */
export async function submitJson(method: 'POST' | 'PUT', path: string, json: string, signal: AbortSignal, key: string, owner: string): Promise<unknown> {
  return mutate(method, path, undefined, signal, key, undefined, owner, json);
}
async function mutate(method: 'POST' | 'PUT' | 'DELETE', path: string, body: unknown, signal: AbortSignal, key?: string, operationCredential?: string, owner?: string, encodedJson?: string): Promise<unknown> {
  // Fresh CSRF per explicit action; never automatically replay a side effect.
  const csrf = await request('/api/auth/csrf', signal);
  if (!object(csrf) || typeof csrf.token !== 'string') throw new ApiError(502, 'INVALID_RESPONSE');
  return request(path, signal, { method, headers: { 'Content-Type': 'application/json',
    'X-CSRF-TOKEN': csrf.token, ...ownerHeaders(owner), ...(key ? { 'Idempotency-Key': key } : {}),
    ...(operationCredential ? { 'X-Operation-Credential': operationCredential } : {}) }, body: encodedJson ?? JSON.stringify(body) });
}
