import { ApiError, object, ownerHeaders, post, request, submitJson } from '../api/request';

export interface AdminProblem {
  id: string;
  problemId: string;
  version: string;
  language: string;
  executionSupported: boolean;
  packageSha256: string;
  available: boolean;
}

export interface ProblemValidation {
  valid: boolean;
  problemId: string | null;
  version: string | null;
  language: string | null;
  executionSupported: boolean;
  packageSha256: string | null;
  hasChallenge: boolean;
  errors: string[];
}

export interface PublishedProblem {
  id: string;
  problemId: string;
  version: string;
  packageSha256: string;
}

const ROOT = '/api/admin/problems';

function invalid(): never {
  throw new ApiError(502, 'INVALID_RESPONSE');
}

function text(value: unknown, max = 256): string {
  if (typeof value !== 'string' || !value.length || value.length > max) return invalid();
  return value;
}

function optionalText(value: unknown, max = 256): string | null {
  if (value === null || value === undefined) return null;
  return text(value, max);
}

export async function listProblems(owner: string, signal: AbortSignal): Promise<AdminProblem[]> {
  const data = await request(ROOT, signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(data)) return invalid();
  return data.map((entry: unknown) => {
    if (!object(entry) || typeof entry.executionSupported !== 'boolean' || typeof entry.available !== 'boolean') {
      return invalid();
    }
    return {
      id: text(entry.id, 64),
      problemId: text(entry.problemId, 128),
      version: text(entry.version, 64),
      language: text(entry.language, 64),
      executionSupported: entry.executionSupported,
      packageSha256: text(entry.packageSha256, 128),
      available: entry.available,
    };
  });
}

export async function validateProblem(owner: string, artifactJson: string, signal: AbortSignal): Promise<ProblemValidation> {
  const csrf = await request('/api/auth/csrf', signal);
  if (!object(csrf) || typeof csrf.token !== 'string') return invalid();
  const response = await fetch(`${ROOT}/validate`, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf.token,
      ...ownerHeaders(owner),
    },
    body: artifactJson,
  });
  const body: unknown = await response.json();
  if (!response.ok && response.status !== 400) {
    throw new ApiError(response.status, object(body) && typeof body.code === 'string' ? body.code : 'REQUEST_FAILED');
  }
  if (!object(body) || body.code !== 'OK' || !object(body.data)) {
    if (response.status === 400 && object(body) && typeof body.code === 'string') {
      throw new ApiError(400, body.code);
    }
    return invalid();
  }
  const value = body.data;
  if (typeof value.valid !== 'boolean' || typeof value.executionSupported !== 'boolean'
    || typeof value.hasChallenge !== 'boolean' || !Array.isArray(value.errors)
    || value.errors.some((item: unknown) => typeof item !== 'string')) {
    return invalid();
  }
  return {
    valid: value.valid,
    problemId: optionalText(value.problemId, 128),
    version: optionalText(value.version, 64),
    language: optionalText(value.language, 64),
    executionSupported: value.executionSupported,
    packageSha256: optionalText(value.packageSha256, 128),
    hasChallenge: value.hasChallenge,
    errors: value.errors.map(item => String(item)),
  };
}

export async function publishProblem(owner: string, artifactJson: string, key: string, signal: AbortSignal): Promise<PublishedProblem> {
  const data = await submitJson('POST', `${ROOT}/publish`, artifactJson, signal, key, owner);
  if (!object(data)) return invalid();
  return {
    id: text(data.id, 64),
    problemId: text(data.problemId, 128),
    version: text(data.version, 64),
    packageSha256: text(data.packageSha256, 128),
  };
}

export async function setProblemAvailability(owner: string, id: string, available: boolean, key: string, signal: AbortSignal): Promise<void> {
  await post(`${ROOT}/${encodeURIComponent(id)}/availability`, { available }, signal, key, undefined, owner);
}

export function problemErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '请求结果未知，请核对当前状态后再试。';
  const map: Record<string, string> = {
    IDEMPOTENCY_CONFLICT: '同一幂等键已绑定不同题包内容，请更换新幂等键或核对原文。',
    PUBLICATION_CONFLICT: '相同 problemId 与 version 已存在且内容不一致，请递增版本号发布。',
    INVALID_REQUEST: '题包 Artifact JSON 格式或编码不合法。',
    REQUEST_TOO_LARGE: '题包 Artifact 超过 1 MiB 大小限制，请精简后再试。',
    RESOURCE_NOT_FOUND: '指定题包版本不存在。',
  };
  if ([401, 403].includes(error.status)) return '需要有效管理员身份，请重新确认登录。';
  return map[error.code] ?? `操作未完成（${error.code}）。`;
}
