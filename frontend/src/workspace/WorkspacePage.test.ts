import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import WorkspacePage from './WorkspacePage.vue';

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
const rejected = (status: number) => new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', data: null }), { status });
let page: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { page?.unmount(); client?.clear(); vi.unstubAllGlobals(); });

async function render(fetcher: typeof fetch) {
  vi.stubGlobal('fetch', fetcher);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', name: 'workspace', component: WorkspacePage },
    { path: '/account', name: 'account', component: { template: '<div />' } },
  ] });
  await router.push('/');
  page = mount(WorkspacePage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  await flushPromises();
  return page;
}

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
    if (path === '/api/exercises/current') return created ? ok({ exerciseId: 'ex1', problemVersionId: 'v1', workspaceInstanceId: 'ws1',
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
