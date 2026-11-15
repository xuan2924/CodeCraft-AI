import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import WorkspaceCommands from './WorkspaceCommands.vue';
import * as api from './commandApi';

vi.mock('./commandApi', async original => ({ ...await original<typeof import('./commandApi')>(),
  history: vi.fn(), detail: vi.fn(), start: vi.fn(), stop: vi.fn(), changes: vi.fn() }));
const exercise = { exerciseId: 'exercise', workspaceInstanceId: 'workspace', problemVersionId: 'problem', state: 'ACTIVE', revision: '1', paths: [], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
const task: api.CommandTask = { id: 'command', exerciseId: 'exercise', workspaceInstanceId: 'workspace', executionInstanceId: 'execution',
  revision: '1', command: 'printf hello', directory: '.', state: 'EXITED', reason: 'command_exit', stopRequested: false, quiescent: true,
  synchronization: 'SYNCED', synchronizationReason: '', synchronizedRevision: '2', changed: true, exitCode: 7,
  logs: [{ channel: 'stderr', text: '<script>PRIVATE_OUTPUT</script>' }], droppedBytes: '12', logsIncomplete: true,
  cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', stateVersion: '3', createdAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:00:01Z' };
const active: api.CommandTask = { ...task, state: 'RUNNING', quiescent: false, synchronization: 'PENDING', synchronizedRevision: null,
  changed: false, completedAt: null, exitCode: null, cleanup: 'PENDING', copyCleanup: 'PENDING', stateVersion: '1', logs: [] };
let view: VueWrapper | undefined;
let client: QueryClient | undefined;
function panel(prepare = vi.fn().mockResolvedValue('1')) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  view = mount(WorkspaceCommands, { props: { owner: 'owner', exercise, credential: 'credential', disabled: false, prepare },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CodeDiff: { props: ['before', 'after'], template: '<pre>{{ before }} → {{ after }}</pre>' } } } });
  return view;
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find(item => item.text() === text);
  if (!found) throw new Error(`Missing button ${text}`);
  return found;
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.history).mockResolvedValue({ commands: [task], nextCursor: null });
  vi.mocked(api.detail).mockResolvedValue(task);
  vi.mocked(api.changes).mockResolvedValue({ commandId: task.id, exerciseId: 'exercise', workspaceInstanceId: 'workspace', executionInstanceId: 'execution',
    baseRevision: '1', changes: [{ kind: 'UPDATE', path: 'src/main/java/Main.java', previousContent: 'before', content: 'after' }] });
});
afterEach(() => { view?.unmount(); client?.clear(); view = undefined; client = undefined; vi.useRealTimers(); });

it('only reads on mount, shows nonzero exit and distinct output loss, and exposes source Diff', async () => {
  const wrapper = panel(); await flushPromises(); await flushPromises();
  expect(wrapper.text()).toContain('退出码 7');
  expect(wrapper.text()).toContain('正式修订 2');
  expect(wrapper.text()).toContain('输出保留窗口已截断');
  expect(wrapper.text()).toContain('部分输出记录缺失');
  expect(wrapper.text()).toContain('<script>PRIVATE_OUTPUT</script>');
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.emitted('changed')).toHaveLength(1);
  expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
  await button(wrapper, '查看命令文件 Diff').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('before → after');
});

it('keeps the original Bash payload and request key when an unknown response survives credential rotation', async () => {
  vi.mocked(api.history).mockResolvedValue({ commands: [], nextCursor: null });
  vi.mocked(api.start).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(active);
  vi.mocked(api.detail).mockResolvedValue(active);
  const prepare = vi.fn().mockResolvedValue('1');
  const wrapper = panel(prepare); await flushPromises();
  await wrapper.get('textarea').setValue('printf hello');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(api.start).toHaveBeenCalledTimes(1);
  expect(wrapper.get('textarea').attributes('disabled')).toBeDefined();
  await wrapper.setProps({ credential: 'rotated', exercise: { ...exercise, revision: '2' } });
  await button(wrapper, '使用原请求键核对，不重复执行').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.start).mock.calls;
  expect(calls).toHaveLength(2);
  expect(calls[1]!.slice(0, 5)).toEqual(calls[0]!.slice(0, 5));
  expect(calls[1]![5]).toBe('rotated');
  expect(calls[1]![3]).toEqual({ command: 'printf hello', directory: '.', expectedRevision: '1' });
  expect(prepare).toHaveBeenCalledTimes(1);
});

it('does not dispatch after a save confirmation returns to a different owner', async () => {
  vi.mocked(api.history).mockResolvedValue({ commands: [], nextCursor: null });
  let complete: (value: string) => void = () => {};
  const prepare = vi.fn(() => new Promise<string>(resolve => { complete = resolve; }));
  const wrapper = panel(prepare); await flushPromises();
  await wrapper.get('textarea').setValue('printf hello');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  await wrapper.setProps({ owner: 'another-owner' });
  complete('1'); await flushPromises();
  expect(api.start).not.toHaveBeenCalled();
  wrapper.unmount(); view = undefined;
  const reads = vi.mocked(api.history).mock.calls.length;
  await vi.advanceTimersByTimeAsync(5000);
  expect(api.history).toHaveBeenCalledTimes(reads);
});

it('switches observations without a late old output replacing the selected command', async () => {
  const other = { ...task, id: 'other', executionInstanceId: 'other-execution', logs: [{ channel: 'stdout', text: 'NEW_OUTPUT' }] };
  vi.mocked(api.history).mockResolvedValue({ commands: [active, other], nextCursor: null });
  let complete: (value: api.CommandTask) => void = () => {};
  const old = new Promise<api.CommandTask>(resolve => { complete = resolve; });
  vi.mocked(api.detail).mockImplementation(async (_owner, _exercise, _workspace, id) => id === active.id ? old : other);
  const wrapper = panel(); await flushPromises();
  await wrapper.get('select[aria-label="选择 Bash 执行记录"]').setValue(other.id); await flushPromises();
  complete({ ...task, logs: [{ channel: 'stdout', text: 'OLD_PRIVATE_OUTPUT' }] }); await flushPromises();
  expect(wrapper.text()).toContain('NEW_OUTPUT');
  expect(wrapper.text()).not.toContain('OLD_PRIVATE_OUTPUT');
  expect(api.start).not.toHaveBeenCalled(); expect(api.stop).not.toHaveBeenCalled();
});

it('reconciles an uncertain stop for its original execution even after selecting history', async () => {
  const other = { ...task, id: 'other', executionInstanceId: 'other-execution' };
  vi.mocked(api.history).mockResolvedValue({ commands: [active, other], nextCursor: null });
  vi.mocked(api.detail).mockImplementation(async (_owner, _exercise, _workspace, id) => id === active.id ? active : other);
  vi.mocked(api.stop).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ ...active, stopRequested: true });
  const wrapper = panel(); await flushPromises(); await flushPromises();
  await button(wrapper, '停止实际命令').trigger('click'); await flushPromises();
  await wrapper.get('select[aria-label="选择 Bash 执行记录"]').setValue(other.id); await flushPromises();
  await button(wrapper, '核对原停止请求').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.stop).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[1]!.slice(0, 5)).toEqual(calls[0]!.slice(0, 5));
  expect(calls[1]![3]).toBe(active.id);
  expect(wrapper.get('select[aria-label="选择 Bash 执行记录"]').element).toHaveProperty('value', other.id);
});
