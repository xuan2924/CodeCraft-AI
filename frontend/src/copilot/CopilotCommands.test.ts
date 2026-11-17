import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotCommands from './CopilotCommands.vue';
import * as api from './commandApi';
import * as execution from '../workspace/commandApi';
import type { Task } from './api';

vi.mock('./commandApi', async original => ({ ...await original<typeof api>(), page: vi.fn(), detail: vi.fn(), decide: vi.fn() }));
vi.mock('../workspace/commandApi', async original => ({ ...await original<typeof execution>(), detail: vi.fn(), changes: vi.fn(), start: vi.fn() }));
const parent: Task = { id: 'parent', exerciseId: 'exercise', state: 'WAITING_APPROVAL', stopRequested: false, reason: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const proposal: api.CommandProposal = { id: 'proposal', sequence: '1', taskId: 'parent', exerciseId: 'exercise', workspaceInstanceId: 'workspace', baseRevision: '1',
  command: 'echo hello; exit 7', directory: '.', mode: 'ASK', modeVersion: '0', state: 'PENDING', commandTaskId: null, decision: null, reason: null,
  result: null, createdAt: parent.createdAt, decidedAt: null, completedAt: null };
const accepted: api.CommandProposal = { ...proposal, state: 'WAITING', decision: 'APPROVE', commandTaskId: 'child', decidedAt: parent.createdAt };
const output: execution.CommandTask = { id: 'child', exerciseId: 'exercise', executionInstanceId: 'execution', workspaceInstanceId: 'workspace', revision: '1',
  command: proposal.command, directory: '.', state: 'EXITED', reason: 'command_exit', stopRequested: false, quiescent: true, synchronization: 'SYNCED',
  synchronizationReason: '', synchronizedRevision: '2', changed: true, exitCode: 7, logs: [{ channel: 'stdout', text: '<script>safe command output</script>' }],
  droppedBytes: '0', logsIncomplete: false, cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', stateVersion: '3', createdAt: parent.createdAt, completedAt: parent.createdAt };
let view: VueWrapper | undefined;
let client: QueryClient | undefined;
function panel() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  view = mount(CopilotCommands, { props: { owner: 'owner', exerciseId: 'exercise', task: parent, credential: 'original', disabled: false },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CodeDiff: { props: ['before', 'after'], template: '<pre>{{ before }} → {{ after }}</pre>' } } } });
  return view;
}
function button(view: VueWrapper, label: string) { return view.findAll('button').find(item => item.text() === label)!; }
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.page).mockResolvedValue({ commands: [proposal], nextBefore: null });
  vi.mocked(api.detail).mockResolvedValue(proposal);
  vi.mocked(execution.detail).mockResolvedValue(output);
  vi.mocked(execution.changes).mockResolvedValue({ commandId: 'child', exerciseId: 'exercise', workspaceInstanceId: 'workspace', executionInstanceId: 'execution',
    baseRevision: '1', changes: [{ kind: 'UPDATE', path: 'src/main/java/Main.java', previousContent: 'before', content: 'after' }] });
});
afterEach(() => { view?.unmount(); client?.clear(); view = undefined; client = undefined; vi.useRealTimers(); });

it('reads a concrete ASK proposal without approving it and sends an explicit rejection', async () => {
  const page = panel(); await flushPromises();
  expect(page.text()).toContain('/project/. $ echo hello; exit 7');
  expect(api.decide).not.toHaveBeenCalled(); expect(execution.start).not.toHaveBeenCalled();
  vi.mocked(api.decide).mockResolvedValue({ ...proposal, state: 'RESOLVED', decision: 'REJECT', reason: 'different approach', decidedAt: parent.createdAt,
    completedAt: parent.createdAt, result: { code: 'COMMAND_REJECTED', reason: 'different approach', result: null, logTruncated: false, omittedLogBytes: '0' } });
  await page.get('textarea').setValue('different approach');
  await button(page, '拒绝此命令').trigger('click'); await flushPromises();
  expect(vi.mocked(api.decide).mock.calls[0]![4]).toEqual({ decision: 'REJECT', reason: 'different approach' });
  expect(page.text()).toContain('COMMAND_REJECTED');
});

it('keeps the original decision intent across credential rotation and ignores an older pending poll', async () => {
  vi.mocked(api.decide).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(accepted);
  const page = panel(); await flushPromises();
  await button(page, '批准此命令').trigger('click'); await flushPromises();
  expect(button(page, '批准此命令').attributes('disabled')).toBeDefined();
  await page.setProps({ credential: 'rotated' });
  await button(page, '核对原命令决定').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.decide).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[1]!.slice(0, 6)).toEqual(calls[0]!.slice(0, 6)); expect(calls[1]![6]).toBe('rotated');
  expect(button(page, '批准此命令')).toBeUndefined();
  expect(page.text()).toContain('等待命令与文件同步收敛');
});

it('shows AUTO as server admission rather than a client-triggered approval', async () => {
  vi.mocked(api.detail).mockResolvedValue({ ...proposal, mode: 'AUTO', modeVersion: '2' });
  const page = panel(); await flushPromises();
  expect(page.text()).toContain('按 AUTO 模式核对准入中');
  expect(button(page, '批准此命令')).toBeUndefined();
  await vi.advanceTimersByTimeAsync(2200); await flushPromises();
  expect(api.decide).not.toHaveBeenCalled(); expect(execution.start).not.toHaveBeenCalled();
});

it('reuses actual command output and Diff without turning nonzero exit into test success', async () => {
  vi.mocked(api.detail).mockResolvedValue(accepted);
  const page = panel(); await flushPromises(); await flushPromises();
  expect(page.text()).toContain('退出码 7'); expect(page.text()).toContain('正式修订 2');
  expect(page.text()).toContain('<script>safe command output</script>'); expect(page.find('script').exists()).toBe(false);
  expect(page.emitted('changed')).toHaveLength(1);
  await button(page, '查看命令文件 Diff').trigger('click'); await flushPromises();
  expect(page.text()).toContain('before → after');
  expect(execution.start).not.toHaveBeenCalled();
});

it('drops an old approval response when its owner or parent task changes and releases polls', async () => {
  let resolve: (value: api.CommandProposal) => void = () => {};
  vi.mocked(api.decide).mockImplementation(() => new Promise(done => { resolve = done; }));
  const page = panel(); await flushPromises();
  await button(page, '批准此命令').trigger('click'); await flushPromises();
  vi.mocked(api.page).mockResolvedValue({ commands: [], nextBefore: null });
  await page.setProps({ owner: 'other', task: { ...parent, id: 'other-parent' } }); await flushPromises();
  resolve(accepted); await flushPromises();
  expect(page.text()).not.toContain('echo hello'); expect(page.emitted('changed')).toBeUndefined();
  page.unmount(); view = undefined;
  const calls = vi.mocked(api.page).mock.calls.length;
  await vi.advanceTimersByTimeAsync(5000);
  expect(api.page).toHaveBeenCalledTimes(calls);
});
