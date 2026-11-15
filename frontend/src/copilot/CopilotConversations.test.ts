import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotConversations from './CopilotConversations.vue';
import CopilotContext from './CopilotContext.vue';
import * as api from './api';

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), conversations: vi.fn(), newConversation: vi.fn(), forkConversation: vi.fn(), latest: vi.fn(), messages: vi.fn(), start: vi.fn(), stop: vi.fn(), watchTask: vi.fn(), tests: vi.fn(), approvalMode: vi.fn(), steers: vi.fn(), context: vi.fn(), compact: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const second = '10000000-0000-0000-0000-000000000002';
const initial: api.Conversation = { id: exercise, exerciseId: exercise, initial: true, createdAt: '2026-01-01T00:00:00Z' };
const created: api.Conversation = { ...initial, id: second, initial: false };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function panel(disabled = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotConversations, { props: { owner: 'owner', exerciseId: exercise, credential: 'control', disabled, prepare: vi.fn().mockResolvedValue(true) },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CopilotUsage: true, CopilotSkills: true, CopilotProposals: true } } });
  wrappers.push(wrapper); return wrapper;
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').find(item => item.text() === label); if (!found) throw new Error(`Missing button ${label}`); return found;
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.conversations).mockResolvedValue([initial, created]);
  vi.mocked(api.newConversation).mockResolvedValue(created);
  vi.mocked(api.latest).mockResolvedValue(null);
  vi.mocked(api.messages).mockResolvedValue({ messages: [], nextCursor: null });
  vi.mocked(api.context).mockResolvedValue({ exerciseId: exercise, version: '0', summaryId: null, throughSequence: '0', summary: null, requests: [] });
  vi.mocked(api.approvalMode).mockResolvedValue({ exerciseId: exercise, mode: 'ASK', version: '0', history: [] });
  vi.mocked(api.tests).mockResolvedValue([]);
  vi.mocked(api.steers).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.watchTask).mockReturnValue(vi.fn());
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); clients.splice(0).forEach(client => client.clear()); vi.useRealTimers(); });

it('forks an explicitly selected message with original intent on retry, without saving or running tools', async () => {
  const message = '10000000-0000-0000-0000-000000000003';
  const branch = { ...created, forkSource: exercise, forkMessage: message };
  vi.mocked(api.messages).mockResolvedValue({ messages: [{ id: message, taskId: message, sequence: '1', kind: 'USER', text: 'fork goal' }], nextCursor: null });
  vi.mocked(api.forkConversation).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(branch);
  vi.mocked(api.conversations).mockResolvedValue([initial, branch]);
  const wrapper = panel(); await flushPromises();
  await button(wrapper, 'Fork 到此消息').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('当前代码不变');
  await button(wrapper, '确认创建对话分支').trigger('click'); await flushPromises();
  await wrapper.setProps({ credential: 'rotated-control' });
  await button(wrapper, '使用原请求键核对新建结果').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.forkConversation).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[1]!.slice(0, 4)).toEqual(calls[0]!.slice(0, 4));
  expect(calls[1]![5]).toBe(calls[0]![5]); expect(calls[1]![4]).toBe('rotated-control');
  expect(wrapper.get('select').element.value).toBe(second);
  expect(wrapper.text()).toContain('对话历史来自过去，文件是当前版本');
  expect(wrapper.props('prepare')).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
});

it('creates an empty conversation without sending a model request, stopping a task or preparing files', async () => {
  const wrapper = panel(); await flushPromises();
  await button(wrapper, 'New · 新会话').trigger('click'); await flushPromises();
  expect(wrapper.get('select').element.value).toBe(second);
  expect(api.newConversation).toHaveBeenCalledTimes(1);
  expect(api.messages).toHaveBeenLastCalledWith('owner', exercise, '-1', expect.any(AbortSignal), second);
  expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
  expect(wrapper.props('prepare')).not.toHaveBeenCalled();
});

it('switches subscriptions and rejects a late old-history response without stopping or replaying the old task', async () => {
  let resolveOld: (value: api.Messages) => void = () => {};
  const oldHistory = new Promise<api.Messages>(resolve => { resolveOld = resolve; });
  const task: api.Task = { id: '10000000-0000-0000-0000-000000000003', exerciseId: exercise, conversationId: exercise,
    state: 'RUNNING', reason: null, stopRequested: false, createdAt: initial.createdAt, completedAt: null };
  const snapshot: api.Snapshot = { task, lastSequence: '1', textHistory: false, partial: { turnId: null, sequence: '0', text: '', truncated: false } };
  vi.mocked(api.latest).mockImplementation(async (_owner, _exercise, _signal, conversation) => conversation === exercise ? snapshot : null);
  vi.mocked(api.messages).mockImplementation(async (_owner, _exercise, _after, _signal, conversation) => conversation === exercise ? oldHistory : { messages: [], nextCursor: null });
  const closed = vi.fn(); vi.mocked(api.watchTask).mockReturnValue(closed);
  const wrapper = panel(); await flushPromises();
  expect(api.watchTask).toHaveBeenCalledTimes(1);
  const oldEvent = vi.mocked(api.watchTask).mock.calls[0]![2];
  await wrapper.get('select').setValue(second); await flushPromises();
  expect(closed).toHaveBeenCalled();
  resolveOld({ messages: [{ id: task.id, taskId: task.id, sequence: '1', kind: 'USER', text: 'OLD_SESSION_SECRET' }], nextCursor: '1' });
  oldEvent(snapshot); await flushPromises();
  expect(wrapper.text()).not.toContain('OLD_SESSION_SECRET');
  expect(wrapper.text()).toContain('尚无 Copilot 任务');
  expect(api.watchTask).toHaveBeenCalledTimes(1);
  expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
  await wrapper.get('select').setValue(exercise); await flushPromises();
  expect(wrapper.text()).toContain('OLD_SESSION_SECRET');
  expect(api.watchTask).toHaveBeenCalledTimes(2);
});

it('retains the selected conversation and unresolved compaction when only control credentials rotate', async () => {
  vi.mocked(api.compact).mockRejectedValue(new TypeError('offline'));
  const wrapper = panel(); await flushPromises();
  await wrapper.get('select').setValue(second); await flushPromises();
  const context = wrapper.findComponent(CopilotContext);
  await context.get('input[type="checkbox"]').setValue(true);
  await button(wrapper, '手动压缩上下文').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('压缩受理结果未知');
  await wrapper.setProps({ credential: 'rotated-control' }); await flushPromises();
  expect(wrapper.get('select').element.value).toBe(second);
  await button(wrapper, '核对原压缩请求').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.compact).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 4)).toEqual(calls[1]?.slice(0, 4));
});

it('reuses the original creation key after an unknown result and keeps ended exercises read-only', async () => {
  vi.mocked(api.newConversation).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(created);
  const wrapper = panel(); await flushPromises();
  await button(wrapper, 'New · 新会话').trigger('click'); await flushPromises();
  expect(button(wrapper, 'New · 新会话').attributes('disabled')).toBeDefined();
  await wrapper.setProps({ credential: 'rotated-control' });
  expect(button(wrapper, 'New · 新会话').attributes('disabled')).toBeDefined();
  await button(wrapper, '使用原请求键核对新建结果').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.newConversation).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.[3]).toEqual(calls[1]?.[3]);
  expect(calls[1]?.[2]).toBe('rotated-control');
  await wrapper.setProps({ disabled: true });
  expect(button(wrapper, 'New · 新会话').attributes('disabled')).toBeDefined();
  expect(wrapper.get('select').attributes('disabled')).toBeUndefined();
});
