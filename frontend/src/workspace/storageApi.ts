import { ApiError, object, request } from '../api/request';

export interface StorageBudget {
  limitBytes: number; recoveryBytes: number; goBytes: number; databaseBytes: number;
  reservedBytes: number; measuredAt: string;
}
function bytes(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0; }
export async function usage(exercise: string, signal: AbortSignal): Promise<StorageBudget> {
  const value = await request(`/api/exercises/${encodeURIComponent(exercise)}/storage`, signal);
  if (!object(value) || !bytes(value.limitBytes) || value.limitBytes === 0 || !bytes(value.recoveryBytes) || value.recoveryBytes >= value.limitBytes
    || !bytes(value.goBytes) || !bytes(value.databaseBytes) || !bytes(value.reservedBytes)
    || !Number.isSafeInteger(value.goBytes + value.databaseBytes + value.reservedBytes)
    || typeof value.measuredAt !== 'string' || !Number.isFinite(Date.parse(value.measuredAt))) throw new ApiError(502, 'INVALID_RESPONSE');
  return { limitBytes: value.limitBytes, recoveryBytes: value.recoveryBytes, goBytes: value.goBytes, databaseBytes: value.databaseBytes,
    reservedBytes: value.reservedBytes, measuredAt: value.measuredAt };
}
