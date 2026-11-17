import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotSteers from './CopilotSteers.vue';
import * as api from './api';
import { ApiError } from '../api/request';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), steers: vi.fn(), sendSteer: vi.fn(), start: vi.fn() }));
const owner = '10000000-0000-0000-0000-000000000001';
const exerciseId = '10000000-0000-0000-0000-000000000002';
const taskId = '10000000-0000-0000-0000-000000000003';
const receipt: api.Steer = { id: '10000000-0000-0000-0000-000000000004', sequence: '1', taskId, exerciseId, actorId: owner, text: 'Keep the public API',
  state: 'RECEIVED', turnId: null, reason: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function panel(state: api.Task['state'] = 'RUNNING', disabled = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const view = mount(CopilotSteers, { props: { owner, exerciseId, taskId, state, credential: 'control', disabled },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(view); return view;
}
function button(view: ReturnType<typeof mount>, label: string) {
  const found = view.findAll('button').find((item) => item.text() === label); if (!found) throw new Error(`Missing ${label}`); return found;
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.steers).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(api.sendSteer).mockResolvedValue(receipt);
});
afterEach(() => { wrappers.splice(0).forEach((view) => view.unmount()); clients.splice(0).forEach((client) => client.clear()); vi.useRealTimers(); });

it('distinguishes reception from effective dispatch and never paints a local edit as applied', async () => {
  let done!: (value: api.Steer) => void;
  vi.mocked(api.sendSteer).mockReturnValue(new Promise((resolve) => { done = resolve; }));
  const view = panel(); await flushPromises(); expect(view.text()).toContain('暂无记录');
  await view.get('textarea').setValue(receipt.text); await button(view, '发送方向调整').trigger('click');
  expect(view.text()).not.toContain('已生效'); expect(view.get('textarea').attributes('disabled')).toBeDefined();
  vi.mocked(api.steers).mockResolvedValue({ items: [receipt], nextCursor: null }); done(receipt); await flushPromises(); await flushPromises();
  expect(view.text()).toContain('已接收，等待安全点'); expect(view.text()).not.toContain('已生效');
  vi.mocked(api.steers).mockResolvedValue({ items: [{ ...receipt, state: 'APPLIED', turnId: taskId, completedAt: receipt.createdAt }], nextCursor: null });
  await view.setProps({ state: 'SUCCEEDED' }); await flushPromises();
  expect(view.text()).toContain('已生效'); expect(api.start).not.toHaveBeenCalled();
});

it('retries only the original instruction and key, including receipt lookup after the task stopped', async () => {
  const stopped: api.Steer = { ...receipt, state: 'NOT_APPLIED', reason: 'USER_STOP', completedAt: receipt.createdAt };
  vi.mocked(api.sendSteer).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(stopped);
  const view = panel(); await flushPromises(); await view.get('textarea').setValue(receipt.text);
  await button(view, '发送方向调整').trigger('click'); await flushPromises();
  expect(view.text()).toContain('受理结果未知'); expect(view.get('textarea').attributes('disabled')).toBeDefined();
  vi.mocked(api.steers).mockResolvedValue({ items: [stopped], nextCursor: null });
  await view.setProps({ state: 'STOPPED', disabled: true }); await flushPromises();
  await button(view, '核对原方向调整').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.sendSteer).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 6)).toEqual(calls[1]?.slice(0, 6));
  expect(view.text()).toContain('原指令未生效'); expect(api.start).not.toHaveBeenCalled();
});

it('does not erase a new account instruction when the previous account response arrives late', async () => {
  let done!: (value: api.Steer) => void;
  vi.mocked(api.sendSteer).mockReturnValue(new Promise((resolve) => { done = resolve; }));
  const view = panel(); await flushPromises(); await view.get('textarea').setValue(receipt.text); await button(view, '发送方向调整').trigger('click');
  await view.setProps({ owner: '20000000-0000-0000-0000-000000000001' }); await flushPromises();
  await view.get('textarea').setValue('New account intent'); done(receipt); await flushPromises();
  expect(view.get('textarea').element.value).toBe('New account intent'); expect(view.text()).not.toContain('服务端已接收');
  expect(api.steers).toHaveBeenCalledTimes(2);
});

it('renders instruction text without HTML and observes without reposting', async () => {
  vi.mocked(api.steers).mockResolvedValue({ items: [{ ...receipt, text: '<script>attack()</script>' }], nextCursor: null });
  const view = panel(); await flushPromises(); await vi.advanceTimersByTimeAsync(4000); await flushPromises();
  expect(view.text()).toContain('<script>attack()</script>'); expect(view.find('script').exists()).toBe(false);
  expect(api.sendSteer).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});

it('shows terminal and read-only states without creating another task', async () => {
  const ended = panel('SUCCEEDED'); await flushPromises();
  expect(ended.text()).toContain('当前任务不能接收新方向调整'); expect(ended.get('textarea').attributes('disabled')).toBeDefined();
  const readonly = panel('RUNNING', true); await flushPromises();
  expect(readonly.get('textarea').attributes('disabled')).toBeDefined(); expect(button(readonly, '发送方向调整').attributes('disabled')).toBeDefined();
  expect(api.sendSteer).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});

it('enforces the UTF-8 bound locally and reports definitive late-task rejection', async () => {
  const view = panel(); await flushPromises(); await view.get('textarea').setValue('中'.repeat(1366));
  await button(view, '发送方向调整').trigger('click'); expect(view.text()).toContain('最多 4 KiB'); expect(api.sendSteer).not.toHaveBeenCalled();
  vi.mocked(api.sendSteer).mockRejectedValue(new ApiError(409, 'COPILOT_STEER_NOT_ACTIVE'));
  await view.get('textarea').setValue(receipt.text); await button(view, '发送方向调整').trigger('click'); await flushPromises();
  expect(view.text()).toContain('指令未受理'); expect(view.text()).not.toContain('受理结果未知'); expect(api.start).not.toHaveBeenCalled();
});

it('shows a read failure without implying that any directive was dispatched', async () => {
  vi.mocked(api.steers).mockRejectedValue(new ApiError(503, 'COPILOT_STORAGE_UNAVAILABLE'));
  const view = panel(); await flushPromises(); expect(view.find('[role="alert"]').exists()).toBe(true);
  expect(view.text()).not.toContain('已生效：'); expect(api.sendSteer).not.toHaveBeenCalled();
});
