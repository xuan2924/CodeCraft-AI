import { afterEach, expect, it, vi } from 'vitest';
import { defineComponent, ref } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import WorkspaceTests from './WorkspaceTests.vue';
import { testTask, type TestTask } from './taskApi';

const exercise = { exerciseId: 'exercise', workspaceInstanceId: 'instance', problemVersionId: 'problem', state: 'ACTIVE', revision: '2', paths: [], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
const result: TestTask = { taskId: 'task', exerciseId: 'exercise', executionInstanceId: 'execution', workspaceInstanceId: 'instance', revision: '0', target: 'base',
  state: 'PASSED', reason: 'authority_report', startedAt: '2026-11-10T00:00:00Z', finishedAt: '2026-11-10T00:00:05Z', exitCode: 0,
  quiescent: true, cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', droppedBytes: '50', logsIncomplete: false,
  logs: [{ channel: 'stdout', text: '<script>window.stolen = true</script>' }],
  problem: { problemId: 'cart-total', version: '1.0.1', packageSha256: 'a'.repeat(64), executionImageDigest: `sha256:${'b'.repeat(64)}`, platform: 'linux/amd64' },
  evidence: { kind: 'SUREFIRE_XML', sha256: 'c'.repeat(64), bytes: 100 } };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
let view: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { view?.unmount(); client?.clear(); vi.unstubAllGlobals(); });

it('rejects graded responses without authoritative evidence or immutable image identity', async () => {
  for (const malformed of [{ ...result, evidence: null }, { ...result, problem: { ...result.problem, executionImageDigest: 'latest' } },
    { ...result, evidence: { ...result.evidence, bytes: 2097153 } }]) {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(malformed)));
    await expect(testTask('exercise', 'instance', 'task', new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
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
