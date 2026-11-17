import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { h } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotDelegations from './CopilotDelegations.vue';
import * as api from './delegationApi';
vi.mock('./delegationApi', () => ({ list: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001'; const parent = '10000000-0000-0000-0000-000000000002';
const first = '10000000-0000-0000-0000-000000000003'; const second = '10000000-0000-0000-0000-000000000004';
const wrappers: ReturnType<typeof mount>[] = []; const clients: QueryClient[] = [];
function row(goal: string, id: string): api.Delegation {
  return { toolId: id, parentTaskId: parent, conversationId: id, goal, state: 'WAITING', resultPayload: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null,
    child: { id, exerciseId: exercise, conversationId: id, state: 'RUNNING', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: null } };
}
function view() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotDelegations, { props: { owner: 'owner', exerciseId: exercise, parentTaskId: parent },
    slots: { default: ({ conversationId }: { conversationId: string }) => h('div', { 'data-child': conversationId }, conversationId) },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } }); wrappers.push(wrapper); return wrapper;
}
beforeEach(() => vi.resetAllMocks());
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); clients.splice(0).forEach(client => client.clear()); });
it('selects retained child identity without starting work and renders goals as text', async () => {
  vi.mocked(api.list).mockResolvedValue([row('<script>not executable</script>', first), row('Second child', second)]);
  const wrapper = view(); await flushPromises(); expect(wrapper.get('[data-child]').attributes('data-child')).toBe(second);
  await wrapper.findAll('button')[0]!.trigger('click'); expect(wrapper.get('[data-child]').attributes('data-child')).toBe(first);
  expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('<script>not executable</script>');
  expect(wrapper.text()).toContain('RUNNING · 等待中'); expect(api.list).toHaveBeenCalledTimes(1);
});
it('does not render a late old-parent relation after the parent scope changes', async () => {
  let finish: (rows: api.Delegation[]) => void = () => {};
  vi.mocked(api.list).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue([]);
  const wrapper = view(); await flushPromises(); await wrapper.setProps({ parentTaskId: second }); await flushPromises();
  finish([row('Old parent child', first)]); await flushPromises();
  expect(wrapper.text()).not.toContain('Old parent child'); expect(wrapper.find('[data-child]').exists()).toBe(false);
});
