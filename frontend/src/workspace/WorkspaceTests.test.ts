import { afterEach, expect, it, vi } from 'vitest';
import { defineComponent, ref } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import WorkspaceTests from './WorkspaceTests.vue';
import { testTask, type TestTask } from './taskApi';
import { useTaskStream } from './useTaskStream';

const exercise = { exerciseId: 'exercise', workspaceInstanceId: 'instance', problemVersionId: 'problem', state: 'ACTIVE', revision: '2', paths: [], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
const result: TestTask = { streamId: null, cursor: null, sequence: null, firstRetainedSequence: null, taskId: 'task', exerciseId: 'exercise', executionInstanceId: 'execution', workspaceInstanceId: 'instance', revision: '0', target: 'base',
  state: 'PASSED', reason: 'authority_report', startedAt: '2026-11-10T00:00:00Z', finishedAt: '2026-11-10T00:00:05Z', exitCode: 0,
  quiescent: true, cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', droppedBytes: '50', logsIncomplete: false,
  logs: [{ channel: 'stdout', text: '<script>window.stolen = true</script>' }],
  problem: { problemId: 'cart-total', version: '1.0.1', packageSha256: 'a'.repeat(64), executionImageDigest: `sha256:${'b'.repeat(64)}`, platform: 'linux/amd64' },
  evidence: { kind: 'SUREFIRE_XML', sha256: 'c'.repeat(64), bytes: 100 } };
const retention = { retainedCompletedLimit: 20, prunedCount: 0, cleanupPendingCount: 0 };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data: Array.isArray(data) ? { tasks: data, retention } : data }), { status: 200 });
let view: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { view?.unmount(); client?.clear(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it('rejects graded responses without authoritative evidence or immutable image identity', async () => {
  for (const malformed of [{ ...result, evidence: null }, { ...result, problem: { ...result.problem, executionImageDigest: 'latest' } },
    { ...result, evidence: { ...result.evidence, bytes: 2097153 } }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(malformed)));
    await expect(testTask('exercise', 'instance', 'task', new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});

it('shows authoritative pruning and distinguishes cleanup still in progress', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path) => ok(String(path).endsWith('/tasks')
    ? { tasks: [{ ...result, logs: [] }], retention: { retainedCompletedLimit: 20, prunedCount: 6, cleanupPendingCount: 2 } } : result)));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceTests, { props: { owner: 'owner', exercise, credential: '' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises(); await flushPromises();
  expect(view.text()).toContain('已精简 6 条');
  expect(view.text()).toContain('2 条仍在后台清理');
  expect(view.text()).not.toContain('尚未交付');
});

it('shows old revisions and truncation, renders logs as text, and only queries on mount', async () => {
  const fetcher = vi.fn<typeof fetch>(async (path, options) => {
    expect(options?.method ?? 'GET').toBe('GET');
    return ok(String(path).endsWith('/tasks') ? [{ ...result, logs: [] }] : result);
  });
  vi.stubGlobal('fetch', fetcher);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceTests, { props: { owner: 'owner', exercise, credential: '' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises(); await flushPromises();
  expect(view.text()).toContain('旧版本结果');
  expect(view.text()).toContain('丢弃 50 字节');
  expect(view.findAll('script')).toHaveLength(0);
  expect(view.get('.test-output').text()).toContain('<script>');
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it('applies ordered live events, ignores duplicates, and recovers gaps without starting a task', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const sources: FakeEvents[] = [];
  class FakeEvents extends EventTarget {
    close = vi.fn();
    constructor(readonly url: string) { super(); sources.push(this); }
  }
  vi.stubGlobal('EventSource', FakeEvents);
  let recovered = false;
  const snapshot = { ...result, state: 'RUNNING', finishedAt: null, evidence: null, quiescent: false,
    streamId: 'browser-stream', cursor: 'opaque3', sequence: '3', firstRetainedSequence: '1', logs: [] };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path, options) => {
    expect(options?.method ?? 'GET').toBe('GET');
    return ok(String(path).endsWith('/tasks') ? [snapshot] : recovered ? { ...snapshot, cursor: 'opaque6', sequence: '6', logs: [{ channel: 'stderr', text: 'recovered tail' }] } : snapshot);
  }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceTests, { props: { owner: 'owner', exercise, credential: '' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises(); await flushPromises();
  expect(sources).toHaveLength(1);
  const send = (sequence: string, text: string) => sources[0]!.dispatchEvent(new MessageEvent('log', { lastEventId: `opaque${sequence}`, data: JSON.stringify({
    schemaVersion: 1, taskId: 'task', executionInstanceId: 'execution', streamId: 'browser-stream', sequence, type: 'log',
    occurredAt: '2026-11-10T00:00:00Z', payload: { channel: 'stdout', data: text } }) }));
  send('4', 'live output'); send('4', 'duplicate');
  await vi.advanceTimersByTimeAsync(99);
  expect(view.get('.test-output').text()).not.toContain('live output');
  await vi.advanceTimersByTimeAsync(1);
  expect(view.get('.test-output').text()).toContain('live output');
  expect(view.get('.test-output').text()).not.toContain('duplicate');
  recovered = true; send('6', 'gap output');
  await flushPromises(); await flushPromises();
  expect(view.get('.test-output').text()).toContain('recovered tail');
  expect(sources[0]!.close).toHaveBeenCalled();
  expect(view.get('.test-output').text()).not.toContain('gap output');
  view.unmount(); view = undefined;
  expect(sources.at(-1)!.close).toHaveBeenCalled();
});

it('backs off failed recovery on a controllable clock and cancels retries on unmount', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const sources: EventTarget[] = [];
  class FakeEvents extends EventTarget {
    close() { /* External transport fixture. */ }
    constructor() { super(); sources.push(this); }
  }
  vi.stubGlobal('EventSource', FakeEvents);
  const attempts: string[] = [];
  vi.stubGlobal('fetch', async (path: RequestInfo | URL) => { attempts.push(String(path)); throw new TypeError('offline'); });
  const snapshot = ref<TestTask>({ ...result, state: 'RUNNING', finishedAt: null, evidence: null, quiescent: false,
    streamId: 'browser-stream', cursor: 'opaque3', sequence: '3', firstRetainedSequence: '1', logs: [] });
  const host = defineComponent({ setup: () => useTaskStream(snapshot, (id) => ['task', id]), template: '<p role="status">{{ status }}</p>' });
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  sources[0]!.dispatchEvent(new Event('error'));
  await vi.advanceTimersByTimeAsync(249);
  expect(attempts).toEqual([]);
  await vi.advanceTimersByTimeAsync(1);
  expect(view.text()).toContain('日志连接暂不可用');
  const endpoint = '/api/exercises/exercise/tasks/task';
  expect(attempts).toEqual([endpoint]);
  await vi.advanceTimersByTimeAsync(999);
  expect(attempts).toEqual([endpoint]);
  await vi.advanceTimersByTimeAsync(1);
  expect(attempts).toEqual([endpoint, endpoint]);
  view.unmount(); view = undefined;
  await vi.advanceTimersByTimeAsync(30000);
  expect(attempts).toEqual([endpoint, endpoint]);
});

it('stops stream recovery on authoritative history eviction without retrying or restarting work', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const sources: EventTarget[] = [];
  class FakeEvents extends EventTarget { close() {} constructor() { super(); sources.push(this); } }
  vi.stubGlobal('EventSource', FakeEvents);
  const fetcher = vi.fn<typeof fetch>(async () => new Response(JSON.stringify({ code: 'TEST_HISTORY_PRUNED' }), { status: 410 }));
  vi.stubGlobal('fetch', fetcher);
  const snapshot = ref<TestTask>({ ...result, streamId: 'browser-stream', cursor: 'opaque3', sequence: '3', firstRetainedSequence: '1' });
  const host = defineComponent({ setup: () => useTaskStream(snapshot, (id) => ['task', id]), template: '<p>{{ status }}</p>' });
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  sources[0]!.dispatchEvent(new Event('error'));
  await vi.advanceTimersByTimeAsync(250);
  expect(view.text()).toContain('较早测试记录已精简');
  await vi.advanceTimersByTimeAsync(30000);
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it('reconnects from the newest cached cursor when a recovery response arrives late', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const sources: FakeEvents[] = [];
  class FakeEvents extends EventTarget {
    close() {}
    constructor(readonly url: string) { super(); sources.push(this); }
  }
  vi.stubGlobal('EventSource', FakeEvents);
  let resolveSnapshot!: (response: Response) => void;
  vi.stubGlobal('fetch', () => new Promise<Response>((resolve) => { resolveSnapshot = resolve; }));
  const snapshot = ref<TestTask>({ ...result, state: 'RUNNING', finishedAt: null, evidence: null, quiescent: false,
    streamId: 'browser-stream', cursor: 'opaque3', sequence: '3', firstRetainedSequence: '1', logs: [] });
  const host = defineComponent({ setup: () => useTaskStream(snapshot, (id) => ['task', id]), template: '<p>{{ status }}</p>' });
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  sources[0]!.dispatchEvent(new Event('error'));
  await vi.advanceTimersByTimeAsync(250);
  const completed = { ...result, streamId: 'browser-stream', cursor: 'opaque6', sequence: '6', firstRetainedSequence: '1' };
  client.setQueryData(['task', 'task'], completed);
  resolveSnapshot(ok({ ...snapshot.value, sequence: '4', cursor: 'opaque4' }));
  await flushPromises(); await flushPromises();
  expect(client.getQueryData<TestTask>(['task', 'task'])?.state).toBe('PASSED');
  expect(sources.at(-1)?.url).toContain('after=opaque6');
});

it('stops only on explicit action and retains the original stop key after a lost response', async () => {
  const keys: string[] = [];
  const running = { ...result, state: 'RUNNING', finishedAt: null, quiescent: false, evidence: null, exitCode: null, logs: [] };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path, options) => {
    if (String(path) === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (options?.method === 'POST') {
      expect(String(path)).toBe('/api/exercises/exercise/tasks/task/stop');
      expect(new Headers(options.headers).get('X-Operation-Credential')).toBe('credential');
      keys.push(new Headers(options.headers).get('Idempotency-Key') ?? '');
      if (keys.length === 1) throw new TypeError('lost response');
      return ok({ ...running, state: 'STOPPING' });
    }
    return ok(String(path).endsWith('/tasks') ? [running] : running);
  }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceTests, { props: { owner: 'owner', exercise, credential: 'credential' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises(); await flushPromises();
  expect(keys).toHaveLength(0);
  await view.findAll('button').find((button) => button.text() === '停止任务')!.trigger('click');
  await flushPromises();
  expect(view.text()).toContain('停止结果尚未确认');
  await view.findAll('button').find((button) => button.text() === '核对原停止请求')!.trigger('click');
  await flushPromises();
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  expect(view.text()).toContain('正在停止');
});

it('does not replace a terminal stream snapshot with a delayed stop response', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const sources: EventTarget[] = [];
  class FakeEvents extends EventTarget {
    close() {}
    constructor() { super(); sources.push(this); }
  }
  vi.stubGlobal('EventSource', FakeEvents);
  const running: TestTask = { ...result, state: 'RUNNING', finishedAt: null, quiescent: false, evidence: null,
    exitCode: null, logs: [], streamId: 'browser-stream', cursor: 'opaque3', sequence: '3', firstRetainedSequence: '1' };
  const stopped: TestTask = { ...running, state: 'STOPPED', reason: 'user_stop', quiescent: true,
    finishedAt: result.finishedAt, cursor: 'opaque5', sequence: '5' };
  let complete = false;
  let resolveStop!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path, options) => {
    if (String(path) === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (options?.method === 'POST') return new Promise<Response>((resolve) => { resolveStop = resolve; });
    const snapshot = complete ? stopped : running;
    return ok(String(path).endsWith('/tasks') ? [snapshot] : snapshot);
  }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceTests, { props: { owner: 'owner', exercise, credential: 'credential' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises(); await flushPromises();
  await view.findAll('button').find((button) => button.text() === '停止任务')!.trigger('click');
  await flushPromises();
  complete = true;
  sources[0]!.dispatchEvent(new MessageEvent('task_result', { lastEventId: 'opaque4', data: JSON.stringify({
    schemaVersion: 1, taskId: 'task', executionInstanceId: 'execution', streamId: 'browser-stream', sequence: '4',
    type: 'task_result', occurredAt: '2026-11-10T00:00:05Z', payload: { state: 'STOPPED', reason: 'user_stop' } }) }));
  await vi.advanceTimersByTimeAsync(100);
  await flushPromises(); await flushPromises();
  expect(view.text()).toContain('已停止');
  resolveStop(ok({ ...running, state: 'STOPPING', cursor: 'opaque4', sequence: '4' }));
  await flushPromises(); await flushPromises();
  expect(view.text()).toContain('已停止');
  expect(client.getQueryData<TestTask>(['workspace', 'owner', 'tests', 'instance', 'detail', 'task'])?.state).toBe('STOPPED');
});

it('retains the original test key and revision after an unknown response without automatic retry', async () => {
  const keys: string[] = [];
  const bodies: string[] = [];
  let signal: AbortSignal | null | undefined;
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path, options) => {
    if (String(path) === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (options?.method === 'POST') {
      keys.push(new Headers(options.headers).get('Idempotency-Key') ?? '');
      bodies.push(String(options.body)); signal = options.signal;
      if (keys.length === 1) throw new TypeError('lost response');
      return ok(result);
    }
    return ok(String(path).endsWith('/tasks') ? [] : result);
  }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const host = defineComponent({ components: { WorkspaceTests }, setup() {
    const panel = ref<{ start: (revision: string) => void }>();
    return { panel, exercise };
  }, template: '<button @click="panel?.start(\'0\')">run</button><WorkspaceTests ref="panel" owner="owner" :exercise="exercise" credential="credential" />' });
  view = mount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  await view.findAll('button').find((button) => button.text() === 'run')!.trigger('click');
  await flushPromises();
  expect(keys).toHaveLength(1);
  expect(view.text()).toContain('启动结果尚未确认');
  await view.findAll('button').find((button) => button.text() === 'run')!.trigger('click');
  await flushPromises();
  expect(keys).toHaveLength(1);
  await view.findAll('button').find((button) => button.text() === '核对并重试原测试请求')!.trigger('click');
  await flushPromises();
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(bodies[0]).toBe(bodies[1]);
  view.unmount(); view = undefined;
  expect(signal?.aborted).toBe(true);
});
