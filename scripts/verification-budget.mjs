// Keep the performance target stable; allow explicit machine/CI-specific headroom.
export function quickBudget(env = process.env) {
  const targetSeconds = 30;
  const timeoutSeconds = Number(env.CODECRAFT_QUICK_TIMEOUT_SECONDS ?? 60);
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds <= targetSeconds || timeoutSeconds > 600) {
    throw new Error('CODECRAFT_QUICK_TIMEOUT_SECONDS must be an integer between 31 and 600');
  }
  return { targetSeconds, timeoutSeconds };
}

export function budgetStatus(seconds, budget) {
  if (seconds >= budget.timeoutSeconds) return 'timeout';
  return seconds > budget.targetSeconds ? 'warning' : 'ok';
}
