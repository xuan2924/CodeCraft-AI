const memoryConsents = new Set<string>();

function storageKey(owner: string): string {
  return `codecraft:copilot-fee-ack:${owner}`;
}

export function hasCopilotFeeConsent(owner: string): boolean {
  if (!owner) return false;
  if (memoryConsents.has(owner)) return true;
  try {
    return globalThis.localStorage?.getItem(storageKey(owner)) === '1';
  } catch {
    return false;
  }
}

export function grantCopilotFeeConsent(owner: string): void {
  if (!owner) return;
  memoryConsents.add(owner);
  try {
    globalThis.localStorage?.setItem(storageKey(owner), '1');
  } catch {
    // Ignore storage errors in restricted browser contexts; in-memory consent still applies for this session.
  }
}

export function clearCopilotFeeConsent(owner?: string): void {
  if (owner) {
    memoryConsents.delete(owner);
    try {
      globalThis.localStorage?.removeItem(storageKey(owner));
    } catch {
      // ignore
    }
    return;
  }
  memoryConsents.clear();
  try {
    const storage = globalThis.localStorage;
    if (!storage) return;
    for (let i = storage.length - 1; i >= 0; i--) {
      const key = storage.key(i);
      if (key?.startsWith('codecraft:copilot-fee-ack:')) storage.removeItem(key);
    }
  } catch {
    // ignore
  }
}

export function formatShortTimestamp(iso: string): string {
  const date = new Date(iso);
  if (!Number.isFinite(date.getTime())) return iso;
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${month}/${day} ${hours}:${minutes}`;
}
