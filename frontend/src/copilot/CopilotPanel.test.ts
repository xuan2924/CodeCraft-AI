import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotPanel from './CopilotPanel.vue';
import * as api from './api';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), latest: vi.fn(), messages: vi.fn(), start: vi.fn(), stop: vi.fn(), watchTask: vi.fn(), tests: vi.fn(), approvalMode: vi.fn(), changeApprovalMode: vi.fn(), steers: vi.fn(), sendSteer: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const task: api.Task = { id: '10000000-0000-0000-0000-000000000002', exerciseId: exercise, state: 'RUNNING', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function panel(prepare = vi.fn().mockResolvedValue(true), disabled = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotPanel, { props: { owner: 'owner', exerciseId: exercise, credential: 'control', disabled, prepare }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(wrapper); return wrapper;
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label); if (!found) throw new Error(`Missing button ${label}`); return found;
}
async function prompt(wrapper: ReturnType<typeof mount>) {
  await flushPromises(); await wrapper.get('textarea').setValue('Inspect my saved code'); await wrapper.get('input[type="checkbox"]').setValue(true);
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.latest).mockResolvedValue(null);
  vi.mocked(api.tests).mockResolvedValue([]);
  vi.mocked(api.steers).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.approvalMode).mockResolvedValue({ exerciseId: exercise, mode: 'ASK', version: '0', history: [] });
  vi.mocked(api.messages).mockResolvedValue({ messages: [], nextCursor: null });
  vi.mocked(api.start).mockResolvedValue(task);
  vi.mocked(api.stop).mockResolvedValue({ ...task, state: 'STOPPING', stopRequested: true });
  vi.mocked(api.watchTask).mockReturnValue(vi.fn());
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((client) => client.clear()); vi.useRealTimers(); });

it('never starts on mount or when draft saving fails', async () => {
  const prepare = vi.fn().mockResolvedValue(false); const wrapper = panel(prepare);
  await flushPromises(); expect(api.start).not.toHaveBeenCalled();
  expect(button(wrapper, '保存草稿并发送').attributes('disabled')).toBeDefined();
  await prompt(wrapper); await button(wrapper, '保存草稿并发送').trigger('click'); await flushPromises();
  expect(prepare).toHaveBeenCalledTimes(1); expect(api.start).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('未创建 Copilot 请求');
});
it('saves first and retains the original key and text after an unknown admission outcome', async () => {
  const prepare = vi.fn().mockResolvedValue(true);
  vi.mocked(api.start).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(task);
  const wrapper = panel(prepare); await prompt(wrapper); await button(wrapper, '保存草稿并发送').trigger('click'); await flushPromises();
  expect(prepare).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('受理结果未知');
  await button(wrapper, '核对原 Copilot 请求').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.start).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 5)).toEqual(calls[1]?.slice(0, 5));
  expect(calls[0]?.slice(0, 4)).toEqual(['owner', exercise, 'control', 'Inspect my saved code']);
  expect(prepare).toHaveBeenCalledTimes(1); expect(wrapper.get('textarea').element.value).toBe('');
});
it('drops a prepared intent after identity changes before saving completes', async () => {
  let done: (value: boolean) => void = () => {};
  const wrapper = panel(vi.fn().mockImplementation(() => new Promise<boolean>((resolve) => { done = resolve; })));
  await prompt(wrapper); await button(wrapper, '保存草稿并发送').trigger('click');
  await wrapper.setProps({ owner: 'another-owner' }); done(true); await flushPromises();
  expect(api.start).not.toHaveBeenCalled(); expect(wrapper.get('textarea').element.value).toBe('');
});
it('reconnects only reads, does not resubscribe for identical snapshots, and allows owner stop without write control', async () => {
  const snapshot: api.Snapshot = { task, lastSequence: '2', textHistory: true, partial: { turnId: task.id, sequence: '1', text: 'Partial response', truncated: false } };
  vi.mocked(api.latest).mockResolvedValue(snapshot);
  const close = vi.fn(); vi.mocked(api.watchTask).mockReturnValue(close);
  const wrapper = panel(undefined, true); await flushPromises();
  expect(api.watchTask).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('只读文本证据');
  expect(wrapper.get('[aria-label="未确认回复预览"]').text()).toContain('Partial response');
  vi.mocked(api.watchTask).mock.calls[0]?.[2](snapshot); await flushPromises();
  expect(api.watchTask).toHaveBeenCalledTimes(1); expect(api.start).not.toHaveBeenCalled();
  await button(wrapper, '停止 Copilot 任务').trigger('click'); await flushPromises();
  expect(api.stop).toHaveBeenCalledTimes(1);
  wrapper.unmount(); wrappers.pop(); expect(close).toHaveBeenCalled(); expect(api.stop).toHaveBeenCalledTimes(1);
});
it('shows the charge uncertainty and incomplete preview after model recovery without restarting', async () => {
  vi.mocked(api.latest).mockResolvedValue({ task: { ...task, state: 'INTERRUPTED', reason: 'MODEL_RESULT_UNKNOWN', completedAt: task.createdAt },
    lastSequence: '1', textHistory: false, partial: { turnId: task.id, sequence: '1', text: 'Incomplete preview', truncated: false } });
  const wrapper = panel(); await flushPromises();
  expect(wrapper.text()).toContain('供应商可能已计费'); expect(wrapper.text()).toContain('不会自动重发');
  expect(wrapper.get('[aria-label="未确认回复预览"]').text()).toContain('Incomplete preview');
  expect(api.start).not.toHaveBeenCalled();
});
it('renders hostile tool text as plain text, not HTML', async () => {
  vi.mocked(api.messages).mockResolvedValue({ messages: [{ id: task.id, taskId: task.id, sequence: '1', kind: 'TOOL', text: '<script>attack()</script><img src=x onerror=attack()>' }], nextCursor: '1' });
  const wrapper = panel(); await flushPromises();
  expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.get('pre').text()).toContain('<script>');
});
