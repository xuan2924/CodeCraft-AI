import { ApiError, object, ownerHeaders, post, request } from '../api/request';
export interface Favorite { problemId: string; title: string; available: boolean }
export async function favorites(owner: string, signal: AbortSignal): Promise<Favorite[]> {
  const data = await request('/api/problem-favorites', signal, { headers: ownerHeaders(owner) });
  if (!Array.isArray(data) || data.length > 1000) throw new ApiError(502, 'INVALID_RESPONSE');
  return data.map((value: unknown) => {
    if (!object(value) || typeof value.problemId !== 'string' || typeof value.title !== 'string' || typeof value.available !== 'boolean') {
      throw new ApiError(502, 'INVALID_RESPONSE');
    }
    return { problemId: value.problemId, title: value.title, available: value.available };
  });
}
export async function setFavorite(owner: string, problem: string, favorite: boolean, signal: AbortSignal): Promise<void> {
  await post(`/api/problem-favorites/${encodeURIComponent(problem)}`, { favorite }, signal, undefined, undefined, owner);
}
