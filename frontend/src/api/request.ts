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
  const body: unknown = await response.json();
  if (!response.ok) throw new ApiError(response.status, object(body) && typeof body.code === 'string' ? body.code : 'REQUEST_FAILED');
  if (!object(body) || body.code !== 'OK' || !('data' in body)) throw new ApiError(502, 'INVALID_RESPONSE');
  return body.data;
}
export async function post(path: string, body: unknown, signal: AbortSignal, key?: string, operationCredential?: string): Promise<unknown> {
  // Fresh CSRF per explicit action; never automatically replay a side effect.
  const csrf = await request('/api/auth/csrf', signal);
  if (!object(csrf) || typeof csrf.token !== 'string') throw new ApiError(502, 'INVALID_RESPONSE');
  return request(path, signal, { method: 'POST', headers: { 'Content-Type': 'application/json',
    'X-CSRF-TOKEN': csrf.token, ...(key ? { 'Idempotency-Key': key } : {}),
    ...(operationCredential ? { 'X-Operation-Credential': operationCredential } : {}) }, body: JSON.stringify(body) });
}
