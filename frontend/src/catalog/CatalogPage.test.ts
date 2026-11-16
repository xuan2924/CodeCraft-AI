import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import CatalogPage from './CatalogPage.vue';
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }));
afterEach(() => vi.unstubAllGlobals());

it('uses published problems and persisted account favorites without inventing progress', async () => {
  let saved = false;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok({ id: 'owner', username: 'user', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' });
    if (path === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (path === '/api/problems') return ok([{ problemId: 'cart', problemVersionId: 'v1', title: '购物车'.repeat(30), version: '1.0', requirements: '计算金额', durationSeconds: 0, editableRoots: [], acceptanceFiles: {}, executionAvailable: true }]);
    if (path === '/api/problem-favorites/cart') { saved = JSON.parse(String(options?.body)).favorite; return ok(null); }
    if (path === '/api/problem-favorites') return ok(saved ? [{ problemId: 'cart', title: '购物车', available: true }] : []);
    if (path === '/api/exercises/current') return new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND' }), { status: 404 });
    throw new Error(`Unexpected fixture request ${path}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'catalog', component: CatalogPage },
    { path: '/workspace', name: 'workspace', component: { template: '<div />' } }, { path: '/history', name: 'history', component: { template: '<div />' } }] });
  await router.push('/');
  const page = mount(CatalogPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  await flushPromises();
  expect(page.text()).toContain('0 分钟');
  await page.get('button[aria-label="收藏题目"]').trigger('click');
  await flushPromises();
  expect(saved).toBe(true);
  await page.get('input[type="checkbox"]').setValue(true);
  expect(page.text()).toContain('计算金额');
  await page.get('button[aria-label="取消收藏"]').trigger('click');
  await flushPromises();
  expect(page.text()).toContain('没有符合筛选的题目');
  page.unmount(); client.clear();
});

it('does not deliver an old favorite failure into a newly switched account', async () => {
  let finish!: (response: Response) => void;
  const mutation: { signal?: AbortSignal | null } = {};
  const identity = { id: 'first', username: 'user', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === '/api/auth/session') return ok(identity);
    if (path === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (path === '/api/problems') return ok([{ problemId: 'cart', problemVersionId: 'v1', title: '购物车', version: '1', requirements: '计算金额', durationSeconds: 60, editableRoots: [], acceptanceFiles: {}, executionAvailable: true }]);
    if (path === '/api/problem-favorites') return ok([]);
    if (path === '/api/problem-favorites/cart') {
      expect(new Headers(options?.headers).get('X-Codecraft-Owner')).toBe('first');
      mutation.signal = options?.signal;
      return new Promise<Response>((resolve) => { finish = resolve; });
    }
    return new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND' }), { status: 404 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: CatalogPage },
    { path: '/history', name: 'history', component: { template: '<div />' } }] });
  await router.push('/');
  const page = mount(CatalogPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  await flushPromises();
  await page.get('button[aria-label="收藏题目"]').trigger('click');
  await flushPromises();
  client.setQueryData(['identity-session'], { ...identity, id: 'second' });
  await flushPromises();
  expect(mutation.signal?.aborted).toBe(true);
  finish(new Response(JSON.stringify({ code: 'INVALID_REQUEST' }), { status: 400 }));
  await flushPromises();
  expect(page.find('[role="alert"]').exists()).toBe(false);
  expect(page.get('button[aria-label="收藏题目"]').attributes('disabled')).toBeUndefined();
  page.unmount(); client.clear();
});
