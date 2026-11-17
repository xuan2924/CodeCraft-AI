import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotFollowups from './CopilotFollowups.vue';
import * as api from './followupApi';
import { ApiError } from '../api/request';
vi.mock('./followupApi', () => ({ recent: vi.fn(), enqueue: vi.fn(), cancel: vi.fn(), resume: vi.fn() }));
const owner = '10000000-0000-0000-0000-000000000001';
const exerciseId = '10000000-0000-0000-0000-000000000002';
const receipt: api.Followup = { id: '10000000-0000-0000-0000-000000000003', sequence: '1', exerciseId, conversationId: exerciseId, text: 'next task', state: 'WAITING', reason: null, taskId: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const cleanup: (() => void)[] = [];
function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = mount(CopilotFollowups, { props: { owner, exerciseId, conversationId: exerciseId, credential: 'control', disabled: false, running: true }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  cleanup.push(() => { view.unmount(); client.clear(); }); return view;
}
function button(view: ReturnType<typeof mount>, label: string) { return view.findAll('button').find(item => item.text() === label)!; }
beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] }); vi.mocked(api.recent).mockResolvedValue([]); vi.mocked(api.enqueue).mockResolvedValue(receipt); });
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.useRealTimers(); });
it('keeps unknown enqueue intent and original key even after the main task finishes', async () => {
  vi.mocked(api.enqueue).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(receipt);
  const view = panel(); await flushPromises(); await view.get('textarea').setValue(receipt.text); await view.get('input').setValue(true);
  await button(view, '加入后续队列（不 steer）').trigger('click'); await flushPromises();
  expect(view.text()).toContain('网络结果未确认'); await view.setProps({ running: false, disabled: true });
  await button(view, '按原意图核对队列操作').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.enqueue).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]!.slice(0, 6)).toEqual(calls[1]!.slice(0, 6));
});
it('reads FIFO receipts without posting on refresh and requires explicit fees before resumption', async () => {
  vi.mocked(api.recent).mockResolvedValue([{ ...receipt, state: 'PAUSED', reason: 'PREDECESSOR_STOPPED', text: '<script>next</script>' }]);
  vi.mocked(api.resume).mockImplementation(async (_o, _e, conversationId, _c, requestKey) => ({ conversationId, requestKey }));
  const view = panel(); await view.setProps({ running: false }); await flushPromises(); await vi.advanceTimersByTimeAsync(4000); await flushPromises();
  expect(view.text()).toContain('暂停'); expect(view.find('script').exists()).toBe(false); expect(api.resume).not.toHaveBeenCalled(); expect(api.enqueue).not.toHaveBeenCalled();
  expect(button(view, '重新授权并继续队列').attributes('disabled')).toBeDefined(); await view.get('input').setValue(true);
  await button(view, '重新授权并继续队列').trigger('click'); await flushPromises(); expect(api.resume).toHaveBeenCalledTimes(1);
});
it('shows definitive queue rejection rather than a false unknown-network result', async () => {
  vi.mocked(api.enqueue).mockRejectedValue(new ApiError(429, 'COPILOT_FOLLOWUP_LIMIT'));
  const view = panel(); await flushPromises(); await view.get('textarea').setValue(receipt.text); await view.get('input').setValue(true);
  await button(view, '加入后续队列（不 steer）').trigger('click'); await flushPromises();
  expect(view.text()).toContain('32 个待执行项'); expect(view.text()).not.toContain('网络结果未确认');
  expect(view.text()).not.toContain('按原意图核对队列操作');
});
it('does not clear another conversation draft on a late enqueue receipt', async () => {
  let finish: (value: api.Followup) => void = () => {};
  vi.mocked(api.enqueue).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  const view = panel(); await flushPromises(); await view.get('textarea').setValue(receipt.text); await view.get('input').setValue(true);
  await button(view, '加入后续队列（不 steer）').trigger('click');
  await view.setProps({ conversationId: '10000000-0000-0000-0000-000000000004' }); await flushPromises(); await view.get('textarea').setValue('other conversation');
  finish(receipt); await flushPromises(); expect(view.get('textarea').element.value).toBe('other conversation');
});
