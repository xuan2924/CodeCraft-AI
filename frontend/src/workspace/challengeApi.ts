import { ApiError, object, ownerHeaders, post, request } from '../api/request';

export interface Challenge {
  available: boolean; entered: boolean; eligible: boolean; reason: string; enteredRevision: string | null; baseTestId: string | null;
  publishedChallenge: { requirements: string; acceptanceFiles: Record<string, string> } | null;
}
function parse(value: unknown): Challenge {
  const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
  if (!object(value) || typeof value.available !== 'boolean' || typeof value.entered !== 'boolean' || typeof value.eligible !== 'boolean'
    || typeof value.reason !== 'string' || (value.enteredRevision !== null && (typeof value.enteredRevision !== 'string' || !/^\d+$/.test(value.enteredRevision)))
    || (value.baseTestId !== null && typeof value.baseTestId !== 'string')) return invalid();
  let publishedChallenge: Challenge['publishedChallenge'] = null;
  if (value.publishedChallenge !== null) {
    const published = value.publishedChallenge;
    if (!value.entered || !object(published) || typeof published.requirements !== 'string' || !object(published.acceptanceFiles)) return invalid();
    const acceptanceFiles: Record<string, string> = {};
    for (const [path, content] of Object.entries(published.acceptanceFiles)) {
      if (typeof content !== 'string') return invalid();
      acceptanceFiles[path] = content;
    }
    publishedChallenge = { requirements: published.requirements, acceptanceFiles };
  }
  if (value.entered && (!value.available || publishedChallenge === null || value.enteredRevision === null || value.baseTestId === null)) return invalid();
  return { available: value.available, entered: value.entered, eligible: value.eligible, reason: value.reason,
    enteredRevision: value.enteredRevision, baseTestId: value.baseTestId, publishedChallenge };
}
const path = (exercise: string) => `/api/exercises/${encodeURIComponent(exercise)}/challenge`;
export async function read(owner: string, exercise: string, signal: AbortSignal): Promise<Challenge> {
  return parse(await request(path(exercise), signal, { headers: ownerHeaders(owner) }));
}
export async function enter(owner: string, exercise: string, key: string, credential: string, signal: AbortSignal): Promise<Challenge> {
  return parse(await post(path(exercise), {}, signal, key, credential, owner));
}
