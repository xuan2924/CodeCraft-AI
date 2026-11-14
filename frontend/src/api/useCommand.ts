import { onScopeDispose, ref, watch } from 'vue';

export interface CommandFailurePolicy {
  definitiveRejection: (failure: unknown) => boolean;
  errorMessage: (failure: unknown) => string;
  identityFailure: (failure: unknown) => boolean;
}
type Operation<T> = (key: string, signal: AbortSignal) => Promise<T>;

/** Component-owned command intent. Unknown outcomes retry only their original key and captured payload. */
export function useCommand<T>(scope: () => string, succeeded: (result: T) => void, policy: CommandFailurePolicy, unauthorized: () => void = () => {}) {
  const pending = ref(false);
  const uncertain = ref(false);
  const error = ref('');
  let controller = new AbortController();
  let generation = 0;
  let disposed = false;
  let attempt: { key: string; operation: Operation<T> } | null = null;
  watch(scope, () => {
    generation++;
    controller.abort(); controller = new AbortController();
    attempt = null; pending.value = false; uncertain.value = false; error.value = '';
  }, { flush: 'sync' });
  onScopeDispose(() => {
    disposed = true; generation++; controller.abort(); attempt = null;
    pending.value = false; uncertain.value = false; error.value = '';
  });
  async function run() {
    if (pending.value || disposed || !attempt) return;
    const current = attempt;
    const token = generation;
    const wasUncertain = uncertain.value;
    pending.value = true; error.value = '';
    let result: T;
    try { result = await current.operation(current.key, controller.signal); }
    catch (failure) {
      if (disposed || token !== generation) return;
      error.value = policy.errorMessage(failure);
      if (!wasUncertain && policy.definitiveRejection(failure)) { attempt = null; uncertain.value = false; }
      else uncertain.value = true;
      if (policy.identityFailure(failure)) unauthorized();
      return;
    } finally { if (!disposed && token === generation) pending.value = false; }
    if (disposed || token !== generation) return;
    attempt = null; uncertain.value = false;
    succeeded(result);
  }
  async function submit(operation: Operation<T>) {
    if (pending.value || disposed) return;
    attempt ??= { key: crypto.randomUUID(), operation };
    await run();
  }
  return { pending, uncertain, error, submit, retry: run };
}
