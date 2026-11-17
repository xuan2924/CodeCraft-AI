import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotTests from './CopilotTests.vue';
import * as api from './api';
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), tests: vi.fn() }));
const record: api.TestRun = { toolId: '10000000-0000-0000-0000-000000000001', taskId: '10000000-0000-0000-0000-000000000002',
  testTaskId: '10000000-0000-0000-0000-000000000003', revision: '1', target: 'base', state: 'WAITING', resultState: null,
  createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotTests, { props: { owner: 'owner', taskId: record.taskId, running: true },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(wrapper); return wrapper;
}
beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] }); vi.mocked(api.tests).mockResolvedValue([record]); });
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((client) => client.clear()); vi.useRealTimers(); });
it('labels the immutable revision and opens its exact original test without starting work', async () => {
  const wrapper = panel(); await flushPromises();
  expect(wrapper.text()).toContain('r1'); expect(wrapper.text()).toContain('执行中');
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('inspect')).toEqual([[record.testTaskId]]);
  expect(api.tests).toHaveBeenCalledTimes(1);
});
it('refetches when the parent finishes and stops polling confirmed results and unmounted panels', async () => {
  const wrapper = panel(); await flushPromises();
  vi.mocked(api.tests).mockResolvedValue([{ ...record, state: 'RESOLVED', resultState: 'FAILED', completedAt: record.createdAt }]);
  await wrapper.setProps({ running: false }); await flushPromises();
  expect(wrapper.text()).toContain('测试失败');
  const count = vi.mocked(api.tests).mock.calls.length;
  await vi.advanceTimersByTimeAsync(4000); await flushPromises(); expect(api.tests).toHaveBeenCalledTimes(count);
  wrapper.unmount(); await vi.advanceTimersByTimeAsync(4000); expect(api.tests).toHaveBeenCalledTimes(count);
});
it('does not display a late result from the previous account', async () => {
  let finish: (value: api.TestRun[]) => void = () => {};
  vi.mocked(api.tests).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; })).mockResolvedValue([]);
  const wrapper = panel(); await flushPromises();
  await wrapper.setProps({ owner: 'other-owner' }); await flushPromises();
  finish([record]); await flushPromises();
  expect(wrapper.text()).not.toContain('r1'); expect(wrapper.find('button').exists()).toBe(false);
});
