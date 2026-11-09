export interface Identity {
  id: string;
  username: string;
  role: 'ADMIN' | 'USER';
  passwordChangeRequired: boolean;
  expiresAt: string;
}
export interface CreatedAccount {
  userId: string;
  operationId: string;
  credentialDelivered: boolean;
  temporaryPassword: string | null;
}
export interface PasswordReset extends CreatedAccount { currentSessionRevoked: boolean }
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function identity(value: unknown): Identity {
  if (!object(value) || typeof value.id !== 'string' || typeof value.username !== 'string'
    || (value.role !== 'ADMIN' && value.role !== 'USER') || typeof value.passwordChangeRequired !== 'boolean'
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))) {
    throw new ApiError(502, 'INVALID_RESPONSE');
  }
  return { id: value.id, username: value.username, role: value.role,
    passwordChangeRequired: value.passwordChangeRequired, expiresAt: value.expiresAt };
}
async function request(path: string, signal: AbortSignal, options: RequestInit = {}): Promise<unknown> {
  const response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store',
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]) });
  if (response.status === 204) return null;
  const body: unknown = await response.json();
  if (!response.ok) throw new ApiError(response.status, object(body) && typeof body.code === 'string' ? body.code : 'REQUEST_FAILED');
  if (!object(body) || body.code !== 'OK' || !('data' in body)) throw new ApiError(502, 'INVALID_RESPONSE');
  return body.data;
}
async function post(path: string, body: unknown, signal: AbortSignal, key?: string): Promise<unknown> {
  // Fetch a fresh context for each explicit action. No automatic replay of credential requests.
  const csrf = await request('/api/auth/csrf', signal);
  if (!object(csrf) || typeof csrf.token !== 'string') throw new ApiError(502, 'INVALID_RESPONSE');
  return request(path, signal, { method: 'POST', headers: { 'Content-Type': 'application/json',
    'X-CSRF-TOKEN': csrf.token, ...(key ? { 'Idempotency-Key': key } : {}) }, body: JSON.stringify(body) });
}
export async function session(signal: AbortSignal): Promise<Identity | null> {
  try { return identity(await request('/api/auth/session', signal)); }
  catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; }
}
export async function login(username: string, password: string, signal: AbortSignal): Promise<Identity> {
  return identity(await post('/api/auth/login', { username, password }, signal));
}
export async function logout(signal: AbortSignal): Promise<void> { await post('/api/auth/logout', {}, signal); }
export async function changePassword(currentPassword: string, newPassword: string, key: string, signal: AbortSignal): Promise<void> {
  await post('/api/auth/password', { currentPassword, newPassword }, signal, key);
}
export async function createAccount(username: string, key: string, signal: AbortSignal): Promise<CreatedAccount> {
  return credential(await post('/api/admin/users', { username }, signal, key));
}
export async function resetPassword(userId: string, key: string, signal: AbortSignal): Promise<PasswordReset> {
  const value = await post(`/api/admin/users/${encodeURIComponent(userId)}/password-reset`, {}, signal, key);
  if (!object(value) || typeof value.currentSessionRevoked !== 'boolean') throw new ApiError(502, 'INVALID_RESPONSE');
  return { ...credential(value), currentSessionRevoked: value.currentSessionRevoked };
}
function credential(value: unknown): CreatedAccount {
  if (!object(value) || typeof value.userId !== 'string' || typeof value.operationId !== 'string'
    || typeof value.credentialDelivered !== 'boolean'
    || (value.credentialDelivered ? typeof value.temporaryPassword !== 'string' : value.temporaryPassword !== null)) {
    throw new ApiError(502, 'INVALID_RESPONSE');
  }
  return { userId: value.userId, operationId: value.operationId, credentialDelivered: value.credentialDelivered,
    temporaryPassword: typeof value.temporaryPassword === 'string' ? value.temporaryPassword : null };
}
export function validUsername(value: string): boolean { return /^[a-zA-Z0-9_-]{3,32}$/.test(value); }
export function validPassword(value: string): boolean {
  const points = Array.from(value);
  return points.length >= 8 && points.length <= 128
    && points.every((point) => { const code = point.codePointAt(0) ?? 0; return code < 0xd800 || code > 0xdfff; });
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '连接中断，操作结果尚未确认。请检查网络后手动重试；改密请先尝试用新密码登录。';
  const messages: Record<string, string> = {
    INVALID_CREDENTIALS: '账号或密码不正确。', INVALID_CURRENT_PASSWORD: '当前密码不正确。',
    INVALID_USERNAME: '账号名需为 3–32 位字母、数字、下划线或连字符。',
    INVALID_PASSWORD: '密码需为 8–128 个 Unicode 码点，不去除空格。',
    USERNAME_EXISTS: '账号名已存在，请换一个账号名。',
    USER_NOT_FOUND: '目标账号不存在，请核对用户标识。',
    INVALID_REQUEST: '请求格式不正确，请核对输入。',
    IDEMPOTENCY_CONFLICT: '这次请求的内容与原请求不同。请恢复原内容后核对，不要重复建号。',
    UNAUTHENTICATED: '登录已过期或被撤销，请重新登录。',
    PASSWORD_CHANGE_REQUIRED: '请先修改临时密码。', FORBIDDEN: '当前账号没有管理员权限。',
    CSRF_REJECTED: '安全凭证已失效，请重试。', RATE_LIMITED: '请求过于频繁，请一分钟后重试。',
    CSRF_CAPACITY: '登录入口暂时繁忙，请稍后重试。', AUTH_BUSY: '登录入口暂时繁忙，请稍后重试。',
    IDENTITY_UNAVAILABLE: '账号服务暂不可用，操作结果未确认，请稍后核对或以原请求重试。',
  };
  return messages[error.code] ?? '无法确认操作结果，请稍后核对，不要重复提交新的请求。';
}
