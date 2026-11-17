import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import WorkspaceRetention from './WorkspaceRetention.vue';

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
const wrappers: VueWrapper[] = [];
const clients: QueryClient[] = [];

afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount());
  clients.splice(0).forEach(c => c.clear());
  vi.unstubAllGlobals();
});

it('displays problem title, version and verification badge, and opens cleanup controls in a dialog', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/exercises') {
      return ok([{
        exerciseId: '5aad76dc-601b-4f7a-86a4-775c757d95cb',
        problemTitle: '购物车金额计算',
        problemVersion: '1.1.0',
        finalVerification: 'PASSED',
        endedAt: '2026-01-01T00:00:00Z',
        retainUntil: '2026-01-31T00:00:00Z',
        deletionState: null,
      }]);
    }
    throw new Error(`Unexpected request ${path}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  const wrapper = mount(WorkspaceRetention, {
    props: { owner: 'owner-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { RouterLink: true } },
  });
  wrappers.push(wrapper);
  await flushPromises();

  expect(wrapper.text()).toContain('购物车金额计算');
  expect(wrapper.text()).toContain('v1.1.0');
  expect(wrapper.text()).toContain('验收通过');
  expect(wrapper.text()).toContain('#5aad76dc');
  expect(wrapper.find('td details').exists()).toBe(false);

  await wrapper.get('input[type="search"]').setValue('购物车');
  expect(wrapper.findAll('tbody tr')).toHaveLength(1);

  await wrapper.findAll('button').find(b => b.text().includes('删除'))!.trigger('click');
  await flushPromises();
  expect(globalThis.document.body.textContent).toContain('删除演练');
});
