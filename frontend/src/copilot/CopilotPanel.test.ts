import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotPanel from './CopilotPanel.vue';
import CopilotReferences from './CopilotReferences.vue';
import CopilotBtw from './CopilotBtw.vue';
import { ApiError } from '../api/request';
import * as api from './api';
import * as delegationApi from './delegationApi';
import CopilotProposals from './CopilotProposals.vue';
vi.mock('./delegationApi', () => ({ list: vi.fn() }));

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), latest: vi.fn(), messages: vi.fn(), start: vi.fn(), stop: vi.fn(), proposals: vi.fn(), watchTask: vi.fn(), tests: vi.fn(), approvalMode: vi.fn(), changeApprovalMode: vi.fn(), steers: vi.fn(), sendSteer: vi.fn(), context: vi.fn(), compact: vi.fn(), cancelCompaction: vi.fn() }));
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
  vi.mocked(delegationApi.list).mockResolvedValue([]);
  vi.mocked(api.proposals).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.context).mockResolvedValue({ exerciseId: exercise, version: '0', summaryId: null, throughSequence: '0', summary: null, requests: [] });
  vi.mocked(api.tests).mockResolvedValue([]);
  vi.mocked(api.steers).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.approvalMode).mockResolvedValue({ exerciseId: exercise, mode: 'ASK', version: '0', history: [] });
  vi.mocked(api.messages).mockResolvedValue({ messages: [], nextCursor: null });
  vi.mocked(api.start).mockResolvedValue(task);
  vi.mocked(api.stop).mockResolvedValue({ ...task, state: 'STOPPING', stopRequested: true });
  vi.mocked(api.watchTask).mockReturnValue(vi.fn());
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((client) => client.clear()); vi.useRealTimers(); });

it('renders the real child through the ordinary task UI without recursive delegation or a second composer', async () => {
  const childId = '10000000-0000-0000-0000-000000000003'; const conversation = '10000000-0000-0000-0000-000000000004';
  const child: api.Task = { ...task, id: childId, conversationId: conversation, state: 'WAITING_APPROVAL' };
  const snapshot = (value: api.Task): api.Snapshot => ({ task: value, lastSequence: '1', textHistory: false, partial: { turnId: null, sequence: '0', text: '', truncated: false } });
  vi.mocked(api.latest).mockImplementation(async (_owner, _exercise, _signal, id) => snapshot(id === conversation ? child : { ...task, state: 'WAITING_CHILD', reason: 'CHILD_RUNNING' }));
  vi.mocked(delegationApi.list).mockResolvedValue([{ toolId: '10000000-0000-0000-0000-000000000005', parentTaskId: task.id, conversationId: conversation,
    goal: 'Inspect delegated code', state: 'WAITING', child, resultPayload: null, createdAt: task.createdAt, completedAt: null }]);
  vi.mocked(api.stop).mockResolvedValue({ ...child, state: 'STOPPING', stopRequested: true });
  const prepare = vi.fn(); const wrapper = panel(prepare); await flushPromises();
  expect(wrapper.findAll('[aria-label="父子任务与交接"]')).toHaveLength(1);
  expect(wrapper.findAll('textarea[id^="copilot-text-"]')).toHaveLength(1);
  expect(wrapper.findAll('select[id^="approval-mode-"]')).toHaveLength(1);
  expect(wrapper.text()).toContain('父 worker 已释放'); expect(wrapper.text()).toContain('不会立即改变子目标'); expect(wrapper.text()).toContain('Inspect delegated code');
  expect(wrapper.findAllComponents(CopilotProposals).map(component => component.props('taskId'))).toContain(childId);
  expect(api.watchTask).toHaveBeenCalledWith(exercise, childId, expect.any(Function), expect.any(Function), expect.any(globalThis.AbortSignal));
  expect(api.messages).toHaveBeenCalledWith('owner', exercise, '-1', expect.any(globalThis.AbortSignal), conversation);
  await button(wrapper, '停止此子任务并交回结果').trigger('click'); await flushPromises();
  expect(api.stop).toHaveBeenCalledWith('owner', exercise, childId, expect.any(String), expect.any(globalThis.AbortSignal));
  expect(api.stop).toHaveBeenCalledTimes(1); expect(api.start).not.toHaveBeenCalled(); expect(prepare).not.toHaveBeenCalled();
});

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
it('keeps the prompt editable and permits removing an unavailable reference after definitive 410 rejection', async () => {
  vi.mocked(api.start).mockRejectedValueOnce(new ApiError(410, 'COPILOT_REFERENCE_UNAVAILABLE')).mockResolvedValue(task);
  const wrapper = panel(); await prompt(wrapper);
  wrapper.getComponent(CopilotReferences).vm.$emit('update:modelValue', [{ kind: 'FILE', source: 'src/Main.java', version: 'workspace:1', offset: 0, limit: 1,
    fingerprint: 'a'.repeat(64), text: 'a', nextOffset: null, retainedBytes: 1, sourceIncomplete: false, droppedBytes: '0' }]);
  await flushPromises(); await button(wrapper, '保存草稿并发送').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('引用来源已不可用'); expect(wrapper.text()).not.toContain('受理结果未知');
  expect(wrapper.get('textarea').element.value).toBe('Inspect my saved code'); expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  expect(button(wrapper, '移除此引用').attributes('disabled')).toBeUndefined(); await button(wrapper, '移除此引用').trigger('click');
  await button(wrapper, '保存草稿并发送').trigger('click'); await flushPromises();
  expect(vi.mocked(api.start).mock.calls[1]![8]).toEqual([]); expect(api.start).toHaveBeenCalledTimes(2);
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
it('brings a side conclusion into an idle draft without sending or replacing existing text', async () => {
  const wrapper = panel(); await prompt(wrapper);
  wrapper.getComponent(CopilotBtw).vm.$emit('bringback', 'Selected side conclusion'); await flushPromises();
  expect(wrapper.get('textarea').element.value).toBe('Inspect my saved code\n\nSelected side conclusion');
  expect(wrapper.get<HTMLInputElement>('input[type="checkbox"]').element.checked).toBe(false);
  expect(wrapper.text()).toContain('尚未发送'); expect(api.start).not.toHaveBeenCalled(); expect(api.sendSteer).not.toHaveBeenCalled();
});
it('uses original-key steer for explicit active-main handoff and never claims reception means effective', async () => {
  vi.mocked(api.latest).mockResolvedValue({ task, lastSequence: '0', textHistory: false, partial: { turnId: null, sequence: '0', text: '', truncated: false } });
  vi.mocked(api.sendSteer).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue({ id: task.id, sequence: '1', taskId: task.id, exerciseId: exercise, actorId: 'owner', text: 'Selected conclusion', state: 'RECEIVED', turnId: null, reason: null, createdAt: task.createdAt, completedAt: null });
  const wrapper = panel(); await flushPromises(); wrapper.getComponent(CopilotBtw).vm.$emit('bringback', 'Selected conclusion'); await flushPromises();
  await button(wrapper, '核对原旁支带回 steer（不换键）').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.sendSteer).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]!.slice(0, 6)).toEqual(calls[1]!.slice(0, 6));
  expect(calls[0]!.slice(0, 5)).toEqual(['owner', exercise, task.id, 'control', 'Selected conclusion']);
  expect(wrapper.text()).toContain('是否生效以主线方向调整记录为准'); expect(api.start).not.toHaveBeenCalled();
});
it('renders hostile tool text as plain text, not HTML', async () => {
  vi.mocked(api.messages).mockResolvedValue({ messages: [{ id: task.id, taskId: task.id, sequence: '1', kind: 'TOOL', text: '<script>attack()</script><img src=x onerror=attack()>' }], nextCursor: '1' });
  const wrapper = panel(); await flushPromises();
  expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.get('pre').text()).toContain('<script>');
});
