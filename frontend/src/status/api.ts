export type Availability = 'AVAILABLE' | 'UNAVAILABLE';
export interface DependencyStatus {
  database: Availability;
  sandbox: Availability;
}

function isAvailability(value: unknown): value is Availability {
  return value === 'AVAILABLE' || value === 'UNAVAILABLE';
}

function isStatus(value: unknown): value is DependencyStatus {
  return typeof value === 'object' && value !== null
    && 'database' in value && isAvailability(value.database)
    && 'sandbox' in value && isAvailability(value.sandbox);
}

export async function fetchStatus(signal: AbortSignal): Promise<DependencyStatus> {
  const response = await fetch('/api/internal/status', {
    signal: AbortSignal.any([signal, AbortSignal.timeout(8000)]),
    cache: 'no-store',
  });
  if (response.status !== 200 && response.status !== 503) throw new Error('STATUS_UNAVAILABLE');
  const body: unknown = await response.json();
  if (!isStatus(body)) throw new Error('INVALID_STATUS_RESPONSE');
  const allAvailable = body.database === 'AVAILABLE' && body.sandbox === 'AVAILABLE';
  if ((response.status === 200) !== allAvailable) throw new Error('INCONSISTENT_STATUS_RESPONSE');
  return body;
}
