import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotBtw from './CopilotBtw.vue';
import * as api from './api';
import * as btw from './btwApi';
vi.mock('./btwApi', () => ({ find: vi.fn(), start: vi.fn() }));
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), latest: vi.fn(), messages: vi.fn(), watchTask: vi.fn(), stop: vi.fn(), start: vi.fn(), sendSteer: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001'; const side = '10000000-0000-0000-0000-000000000002';
const task: api.Task = { id: '10000000-0000-0000-0000-000000000003', exerciseId: exercise, conversationId: side, state: 'SUCCEEDED', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:00:01Z' };
const snapshot: api.Snapshot = { task, lastSequence: '2', textHistory: false, partial: { turnId: null, sequence: '0', text: '', truncated: false } };
const cleanup: (() => void)[] = [];
function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = mount(CopilotBtw, { props: { owner: 'owner', exerciseId: exercise, parentConversationId: exercise, credential: 'control', disabled: false, mainRunning: true }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CopilotUsage: true } } });
  cleanup.push(() => { view.unmount(); client.clear(); }); return view;
}
function button(view: ReturnType<typeof mount>, label: string) { return view.findAll('button').find(item => item.text() === label)!; }
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(btw.find).mockResolvedValue({ exerciseId: exercise, parentConversationId: exercise, conversationId: side, createdAt: task.createdAt });
  vi.mocked(api.latest).mockResolvedValue(snapshot); vi.mocked(api.messages).mockResolvedValue({ messages: [{ id: task.id, taskId: task.id, sequence: '2', kind: 'ASSISTANT', text: 'Side conclusion' }], nextCursor: '2' });
  vi.mocked(api.watchTask).mockReturnValue(vi.fn()); vi.mocked(btw.start).mockResolvedValue(task);
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.useRealTimers(); });
it('opens and reads only its side identity, and requires explicit preview before bringing back', async () => {
  const view = panel(); await flushPromises(); await flushPromises();
  expect(btw.start).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
  expect(vi.mocked(api.messages).mock.calls.every(call => call[4] === side)).toBe(true);
  await button(view, '选择此回复并预览带回').trigger('click'); expect(view.emitted('bringback')).toBeUndefined();
  await button(view, '将预览结论作为 steer 发送到主任务').trigger('click'); expect(view.emitted('bringback')![0]![0]).toContain('Side conclusion');
  expect(view.emitted('bringback')![0]![0]).toContain(task.id);
  await view.setProps({ mainRunning: false }); expect(button(view, '将预览结论填入主请求（尚未发送）')).toBeDefined();
  expect(api.sendSteer).not.toHaveBeenCalled();
});
it('keeps the original side text and key after unknown admission even when new asks become disabled', async () => {
  vi.mocked(btw.start).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(task);
  const view = panel(); await flushPromises(); await flushPromises(); await view.get('textarea').setValue('Side question'); await view.get('input').setValue(true);
  await button(view, '发送旁支问题（不替换主任务）').trigger('click'); await flushPromises(); await view.setProps({ disabled: true });
  await button(view, '核对原旁支请求（不换键）').trigger('click'); await flushPromises();
  const calls = vi.mocked(btw.start).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]!.slice(0, 6)).toEqual(calls[1]!.slice(0, 6)); expect(api.start).not.toHaveBeenCalled();
});
it('shows incomplete streaming output and stops only the side task, never treating partial text as a handoff', async () => {
  vi.mocked(api.latest).mockResolvedValue({ ...snapshot, task: { ...task, state: 'RUNNING', completedAt: null }, partial: { turnId: exercise, sequence: '3', text: 'Incomplete side', truncated: true } });
  vi.mocked(api.messages).mockResolvedValue({ messages: [], nextCursor: null }); vi.mocked(api.stop).mockResolvedValue({ ...task, state: 'STOPPING', stopRequested: true });
  const view = panel(); await flushPromises(); await flushPromises();
  expect(view.get('[aria-label="旁支未确认流式回复"]').text()).toContain('Incomplete side'); expect(view.text()).not.toContain('选择此回复并预览带回');
  expect(api.watchTask).toHaveBeenCalledWith(exercise, task.id, expect.any(Function), expect.any(Function), expect.any(AbortSignal));
  await button(view, '仅停止旁支').trigger('click'); await flushPromises(); expect(vi.mocked(api.stop).mock.calls[0]!.slice(0, 3)).toEqual(['owner', exercise, task.id]); expect(btw.start).not.toHaveBeenCalled();
});
it('ignores a late old-conversation receipt instead of clearing the new side draft', async () => {
  let finish: (value: api.Task) => void = () => {};
  vi.mocked(btw.start).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  const view = panel(); await flushPromises(); await flushPromises(); await view.get('textarea').setValue('Old question'); await view.get('input').setValue(true);
  await button(view, '发送旁支问题（不替换主任务）').trigger('click');
  vi.mocked(btw.find).mockResolvedValue(null); await view.setProps({ parentConversationId: task.id }); await flushPromises(); await view.get('textarea').setValue('New side draft');
  finish(task); await flushPromises(); expect(view.get('textarea').element.value).toBe('New side draft'); expect(view.emitted('bringback')).toBeUndefined();
});
it('never falls back to main history when the side has not yet been created', async () => {
  vi.mocked(btw.find).mockResolvedValue(null);
  const view = panel(); await flushPromises(); await view.get('textarea').setValue('First side'); await view.get('input').setValue(true);
  await button(view, '发送旁支问题（不替换主任务）').trigger('click'); await flushPromises(); expect(api.messages).not.toHaveBeenCalled(); expect(api.latest).not.toHaveBeenCalled();
});
