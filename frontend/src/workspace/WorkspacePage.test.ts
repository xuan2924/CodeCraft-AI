import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import WorkspacePage from './WorkspacePage.vue';
import * as drafts from './drafts';
vi.mock('./drafts', async (original) => ({ ...await original<typeof drafts>(), listAccountDrafts: vi.fn(async () => []), confirmExerciseDeletion: vi.fn(async () => {}) }));

const endPreparation = vi.hoisted(() => vi.fn<(choice: 'save' | 'discard' | 'retain') => Promise<boolean>>());
vi.mock('./WorkspaceEditor.vue', () => ({ __esModule: true, default: { props: ['credential'], data: () => ({ draft: '' }),
  methods: { prepareEnd: (choice: 'save' | 'discard' | 'retain') => endPreparation(choice) },
  template: '<textarea aria-label="草稿夹具" v-model="draft" :data-can-write="!!credential" />' } }));

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
const rejected = (status: number) => new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', data: null }), { status });
let page: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { page?.unmount(); client?.clear(); vi.unstubAllGlobals(); vi.useRealTimers(); endPreparation.mockReset(); vi.mocked(drafts.listAccountDrafts).mockReset().mockResolvedValue([]); vi.mocked(drafts.confirmExerciseDeletion).mockClear(); });

async function render(fetcher: typeof fetch, initial = '/') {
  vi.stubGlobal('fetch', (input: RequestInfo | URL, options?: RequestInit) => {
    if (String(input) === '/api/exercises' && !options?.method) return Promise.resolve(ok([]));
    if (String(input).endsWith('/storage') && !options?.method) return Promise.resolve(ok({ limitBytes: 536870912, recoveryBytes: 268435456,
      goBytes: 0, databaseBytes: 0, reservedBytes: 0, measuredAt: '2026-01-01T00:00:00Z' }));
    return fetcher(input, options);
  });
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', name: 'workspace', component: WorkspacePage },
    { path: '/account', name: 'account', component: { template: '<div />' } },
  ] });
  await router.push(initial);
  page = mount(WorkspacePage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  await flushPromises();
  return page;
}

it('clears only the authoritatively deleted exercise on reconnect, not generic resource errors', async () => {
  let deleted = false;
  const fetcher = vi.fn<typeof fetch>(async (input) => {
    if (String(input) === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' });
    if (String(input) === '/api/problems') return ok([]);
    if (String(input) === '/api/exercises/old' && deleted) return new Response(JSON.stringify({ code: 'EXERCISE_DELETED', data: null }), { status: 404 });
    return rejected(404);
  });
  await render(fetcher, '/?exercise=old');
  expect(drafts.confirmExerciseDeletion).not.toHaveBeenCalled();
  deleted = true;
  await client!.invalidateQueries({ queryKey: ['workspace', 'owner', 'current'] });
  await flushPromises();
  expect(drafts.confirmExerciseDeletion).toHaveBeenCalledWith('owner', 'old');
  expect(page!.text()).toContain('演练已删除');
  expect(page!.find('textarea').exists()).toBe(false);
});

it('retains the original mutation key after a lost response and aborts requests on unmount', async () => {
  const keys: string[] = [];
  const bodies: string[] = [];
  let mutationSignal: AbortSignal | null | undefined;
  const fetcher = vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([{ problemVersionId: 'v1', title: '购物车总价', version: '1.0.0', requirements: '计算总价', durationSeconds: 2700, editableRoots: [], acceptanceFiles: {} }]);
    if (path === '/api/exercises/current') return rejected(404);
    if (path === '/api/exercises') {
      keys.push(new Headers(options?.headers).get('Idempotency-Key') ?? '');
      bodies.push(String(options?.body));
      mutationSignal = options?.signal;
      if (keys.length === 1) throw new TypeError('lost response');
      return ok({ exerciseId: 'e1', state: 'PENDING' });
    }
    throw new Error('Unexpected request');
  });
  const view = await render(fetcher);
  await view.get('[data-testid="create-workspace"]').trigger('click');
  await flushPromises();
  expect(view.get('[role="alert"]').text()).toContain('无法确认结果');
  expect(view.get('[data-testid="create-workspace"]').attributes('disabled')).toBeDefined();
  const retry = view.findAll('button').find((button) => button.text() === '核对并重试原操作');
  expect(retry).toBeDefined();
  await retry?.trigger('click');
  await flushPromises();
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(bodies[0]).toBe(bodies[1]);
  view.unmount();
  page = undefined;
  expect(mutationSignal?.aborted).toBe(true);
});

it('discards an old account mutation and ignores its late 401 after an account switch', async () => {
  let finish!: (value: Response) => void;
  let oldSignal: AbortSignal | null | undefined;
  const identityA = { id: 'owner-a', username: 'alice', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' };
  const identityB = { ...identityA, id: 'owner-b', username: 'bob' };
  const view = await render(vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok(identityA);
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([{ problemVersionId: 'v1', title: '购物车总价', version: '1.0.0', requirements: '计算总价', durationSeconds: 2700, editableRoots: [], acceptanceFiles: {} }]);
    if (path === '/api/exercises/current') return rejected(404);
    if (path === '/api/exercises') {
      oldSignal = options?.signal;
      return new Promise<Response>((resolve) => { finish = resolve; });
    }
    throw new Error('Unexpected request');
  }));
  await view.get('[data-testid="create-workspace"]').trigger('click');
  await flushPromises();
  client?.setQueryData(['identity-session'], identityB);
  await flushPromises();
  expect(oldSignal?.aborted).toBe(true);
  expect(view.text()).not.toContain('核对并重试原操作');
  finish(new Response(JSON.stringify({ code: 'UNAUTHENTICATED', data: null }), { status: 401 }));
  await flushPromises();
  expect(client?.getQueryData(['identity-session'])).toEqual(identityB);
  expect(view.find('[role="alert"]').exists()).toBe(false);
});

it('does not clear a new account action when an old navigation finishes', async () => {
  const identity = { id: 'owner-a', username: 'alice', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
  let creates = 0;
  const view = await render(vi.fn<typeof fetch>(async (input) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok(identity);
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([{ problemVersionId: 'v1', title: '题目', version: '1', requirements: '', durationSeconds: 60, editableRoots: [], acceptanceFiles: {} }]);
    if (path === '/api/exercises') {
      if (++creates === 1) return ok({ exerciseId: 'old-created', state: 'PENDING' });
      throw new TypeError('lost response for new account');
    }
    return rejected(404);
  }));
  let finishNavigation!: () => void;
  const navigation = vi.spyOn(view.vm.$router, 'replace').mockImplementationOnce(() => new Promise((resolve) => { finishNavigation = () => resolve(undefined); }));
  try {
    await view.get('[data-testid="create-workspace"]').trigger('click');
    await flushPromises();
    client?.setQueryData(['identity-session'], { ...identity, id: 'owner-b', username: 'bob' });
    await flushPromises();
    await view.get('[data-testid="create-workspace"]').trigger('click');
    await flushPromises();
    expect(view.text()).toContain('核对并重试原操作');
    finishNavigation();
    await flushPromises();
    expect(view.text()).toContain('核对并重试原操作');
  } finally { navigation.mockRestore(); }
});

it('does not show private data when authentication cannot be confirmed', async () => {
  const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'));
  const view = await render(fetcher);
  expect(view.get('[role="alert"]').text()).toContain('无法确认登录状态');
  expect(view.find('[data-testid="create-workspace"]').exists()).toBe(false);
  expect(view.find('[data-testid="source"]').exists()).toBe(false);
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it('selects a published version and displays only confirmed workspace files', async () => {
  let created = false;
  const fetcher = vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([{ problemVersionId: 'v1', title: '购物车总价', version: '1.0.0', requirements: '按整数分计算总价', durationSeconds: 2700,
      editableRoots: ['src/main/java', 'src/test/java'], acceptanceFiles: { 'CartTotalTest.java': 'public class CartTotalTest {}' } }]);
    if (path === '/api/exercises' && options?.method === 'POST') {
      expect(JSON.parse(String(options.body))).toEqual({ problemVersionId: 'v1' });
      expect(new Headers(options.headers).get('Idempotency-Key')).toBeTruthy();
      created = true;
      return ok({ operationId: 'op1', exerciseId: 'ex1', state: 'PENDING', queryUrl: '/api/operations/op1' });
    }
    if (path === '/api/exercises/current' || path === '/api/exercises/ex1') return created ? ok({ exerciseId: 'ex1', problemVersionId: 'v1', workspaceInstanceId: 'ws1',
      preparationOperationId: 'op1', state: 'READY', revision: '0', stateVersion: '2', paths: ['src/main/java/interview/CartTotal.java'], startedAt: null, deadlineAt: null,
      reportAvailability: 'UNAVAILABLE_NOT_IMPLEMENTED', preparationFailureReason: null, cleanupState: null }) : rejected(404);
    if (path.includes('/files?')) return ok({ workspaceInstanceId: 'ws1', revision: '0', path: 'src/main/java/interview/CartTotal.java', content: 'public class CartTotal {}' });
    throw new Error('Unexpected request: ' + path);
  });
  vi.stubGlobal('fetch', fetcher);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'workspace', component: WorkspacePage }, { path: '/account', name: 'account', component: { template: '<div />' } }] });
  await router.push('/');
  page = mount(WorkspacePage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  await flushPromises();
  expect(page.text()).toContain('工程底座');
  await page.get('[data-testid="create-workspace"]').trigger('click');
  await flushPromises();
  expect(page.text()).toContain('已准备，仅可浏览');
  await page.get('[data-testid="workspace-file"]').trigger('click');
  await flushPromises();
  expect(page.get('pre[data-testid="source"]').text()).toContain('public class CartTotal');
  expect(page.find('textarea').exists()).toBe(false);
});

it('retains the mounted active editor on background failures and exposes file retry errors', async () => {
  let offline = false;
  let controlOffline = false;
  const view = await render(vi.fn<typeof fetch>(async (input) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' });
    if (path === '/api/problems') return ok([]);
    if (path === '/api/exercises/current' || path === '/api/exercises/ex1') {
      if (offline) throw new TypeError('offline');
      return ok({ exerciseId: 'ex1', problemVersionId: 'v1', workspaceInstanceId: 'ws1', state: 'ACTIVE', revision: '0', paths: ['src/main/java/Main.java'], deadlineAt: null, preparationFailureReason: null, cleanupState: null });
    }
    if (path.endsWith('/control') && controlOffline) throw new TypeError('control offline');
    if (path.endsWith('/control')) return ok({ exerciseId: 'ex1', operationId: null, state: 'SUCCEEDED', generation: '0', held: false, credential: null });
    if (path.includes('/files?')) return rejected(503);
    throw new Error('Unexpected request');
  }));
  await flushPromises();
  await view.get('textarea').setValue('unsaved without IndexedDB');
  await view.get('[data-testid="workspace-file"]').trigger('click');
  await flushPromises();
  expect(view.text()).toContain('重试文件读取');
  controlOffline = true;
  await client?.invalidateQueries({ queryKey: ['workspace', 'owner', 'control'] });
  await flushPromises();
  expect(view.text()).toContain('无法确认操作权，已暂停写入');
  controlOffline = false;
  await view.findAll('button').find((button) => button.text() === '刷新查询')!.trigger('click');
  await flushPromises();
  expect(view.text()).not.toContain('无法确认操作权，已暂停写入');
  offline = true;
  await client?.invalidateQueries({ queryKey: ['workspace', 'owner', 'current'] });
  await flushPromises();
  expect(view.get('textarea').element.value).toBe('unsaved without IndexedDB');
  expect(view.get('textarea').attributes('data-can-write')).toBe('false');
});

it('requires draft handling before ending and retains the ended result by exercise id', async () => {
  let ended = false;
  let ends = 0;
  endPreparation.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
  const exercise = { exerciseId: 'ex1', problemVersionId: 'v1', workspaceInstanceId: 'ws1', state: 'ACTIVE', revision: '7', paths: [],
    deadlineAt: new Date(Date.now() + 60000).toISOString(), preparationFailureReason: null, cleanupState: null };
  const view = await render(vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([]);
    if (path === '/api/exercises/current') return ended ? rejected(404) : ok(exercise);
    if (path === '/api/exercises/ex1') return !ended ? ok(exercise) : ok({ ...exercise, state: 'ENDED', finalization: { operationId: 'end', finalRevision: '7', verification: 'UNAVAILABLE', resultTaskId: null,
      attempts: 3, maxAttempts: 3, deadlineAt: exercise.deadlineAt, endedAt: new Date().toISOString(), status: 'ENDED' } });
    if (path.endsWith('/tasks')) return ok([]);
    if (path.endsWith('/control') || path.endsWith('/control/acquire')) return ok({ exerciseId: 'ex1', operationId: null, state: 'SUCCEEDED', generation: '1', held: !!options?.method,
      credential: options?.method ? 'a'.repeat(43) : null });
    if (path.endsWith('/end')) { ends++; ended = true; return ok({ exerciseId: 'ex1', state: 'CLOSING' }); }
    throw new Error('Unexpected request: ' + path);
  }));
  await view.findAll('button').find((button) => button.text() === '申请操作权')!.trigger('click');
  await flushPromises();
  await view.get('[data-testid="end-exercise"]').trigger('click');
  await view.findAll('button').find((button) => button.text() === '保存后结束')!.trigger('click');
  await flushPromises();
  expect(ends).toBe(0);
  expect(view.text()).toContain('请先处理草稿或未确认的保存');
  await view.findAll('button').find((button) => button.text() === '保存后结束')!.trigger('click');
  await flushPromises();
  expect(ends).toBe(1);
  expect(view.text()).toContain('最终验收结果不可用');
  expect(view.text()).toContain('AI 报告尚未接入');
  expect(view.find('[data-testid="end-exercise"]').exists()).toBe(false);
});

it('can explicitly retain drafts and end after reload without reacquiring ordinary page authority', async () => {
  let ended = false;
  const exercise = { exerciseId: 'full', problemVersionId: 'v1', workspaceInstanceId: 'ws1', state: 'ACTIVE', revision: '7', paths: [],
    deadlineAt: new Date(Date.now() + 60000).toISOString(), preparationFailureReason: null, cleanupState: null };
  endPreparation.mockResolvedValue(true);
  const view = await render(vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([]);
    if (path === '/api/exercises/current' || path === '/api/exercises/full') return ok(ended ? { ...exercise, state: 'CLOSING' } : exercise);
    if (path.endsWith('/control')) return ok({ exerciseId: 'full', operationId: null, state: 'SUCCEEDED', generation: '1', held: true, credential: null });
    if (path.endsWith('/recovery-end')) {
      expect(new Headers(options?.headers).has('X-Operation-Credential')).toBe(false);
      ended = true; return ok({ exerciseId: 'full', state: 'CLOSING' });
    }
    if (path.endsWith('/tasks')) return ok({ tasks: [], retention: { retainedCompletedLimit: 20, prunedCount: 0, cleanupPendingCount: 0 } });
    throw new Error('Unexpected ordinary action: ' + path);
  }));
  await view.get('textarea').setValue('retain this local draft');
  expect(view.get('[data-testid="end-exercise"]').attributes('disabled')).toBeUndefined();
  await view.get('[data-testid="end-exercise"]').trigger('click');
  await view.findAll('button').find((button) => button.text() === '保留草稿并结束')!.trigger('click');
  await flushPromises();
  expect(ended).toBe(true); expect(endPreparation).toHaveBeenCalledWith('retain');
  expect(view.get('textarea').element.value).toBe('retain this local draft');
  expect(drafts.confirmExerciseDeletion).not.toHaveBeenCalled();
});

it('reopens an ended result and its draft backups even when another exercise is active', async () => {
  vi.mocked(drafts.listAccountDrafts).mockResolvedValue([{ owner: 'owner', exercise: 'old', path: 'removed.java', id: 'old-page', revision: '0',
    baseContent: 'base', content: 'backed up before expiry', updatedAt: 1 }]);
  const fetcher = vi.fn<typeof fetch>(async (input) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'fixture' });
    if (path === '/api/problems') return ok([{ problemVersionId: 'v1', title: '题目', version: '1', requirements: '', durationSeconds: 60, editableRoots: [], acceptanceFiles: {} }]);
    if (path === '/api/exercises') return ok({ exerciseId: 'new', state: 'PENDING' });
    if (path === '/api/exercises/new') return ok({ exerciseId: 'new', problemVersionId: 'v1', workspaceInstanceId: 'new-ws', state: 'PREPARING', revision: null, paths: [],
      deadlineAt: null, preparationFailureReason: null, cleanupState: null });
    if (path === '/api/exercises/current') return ok({ exerciseId: 'new', state: 'ACTIVE' });
    if (path === '/api/exercises/old') return ok({ exerciseId: 'old', problemVersionId: 'v1', workspaceInstanceId: 'old-ws', state: 'ENDED', revision: '7', paths: [],
      deadlineAt: null, preparationFailureReason: null, cleanupState: null });
    if (path.endsWith('/tasks')) return ok([]);
    throw new Error('Unexpected request: ' + path);
  });
  const view = await render(fetcher, '/?exercise=old');
  expect(view.text()).toContain('已结束 · 修订 7');
  expect(view.get('[aria-label="结束后的本地草稿"]').text()).toContain('backed up before expiry');
  expect(fetcher.mock.calls.some(([path]) => String(path) === '/api/exercises/current')).toBe(false);
  await view.get('[data-testid="create-workspace"]').trigger('click');
  await flushPromises();
  expect(view.text()).toContain('正在准备，结果未确认');
  expect(view.find('[aria-label="结束后的本地草稿"]').exists()).toBe(false);
});

it('counts down without resetting and does not submit drafts at expiry', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
  const fetcher = vi.fn<typeof fetch>(async (input) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' });
    if (path === '/api/problems') return ok([]);
    if (path === '/api/exercises/current' || path === '/api/exercises/ex1') return ok({ exerciseId: 'ex1', problemVersionId: 'v1', workspaceInstanceId: 'ws1', state: 'ACTIVE', revision: '0', paths: [],
      deadlineAt: '2026-01-01T00:00:02Z', preparationFailureReason: null, cleanupState: null });
    if (path.endsWith('/tasks')) return ok([]);
    if (path.endsWith('/control')) return ok({ exerciseId: 'ex1', operationId: null, state: 'SUCCEEDED', generation: '0', held: false, credential: null });
    throw new Error('Unexpected request: ' + path);
  });
  const view = await render(fetcher);
  await view.get('textarea').setValue('draft remains local');
  expect(view.text()).toContain('00:02');
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
  expect(view.text()).toContain('作答已到期');
  expect(view.get('textarea').element.value).toBe('draft remains local');
  expect(fetcher.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});
