import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api/request';
import WorkspaceHistory from './WorkspaceHistory.vue';
import * as api from './historyApi';
vi.mock('./historyApi', async original => ({ ...await original<typeof import('./historyApi')>(), history: vi.fn(), preview: vi.fn(), restore: vi.fn(), operation: vi.fn() }));
const exercise = { exerciseId: 'exercise', workspaceInstanceId: 'workspace', problemVersionId: 'problem', state: 'ACTIVE', revision: '2', paths: [], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
const change: api.CodeChange = { id: 'change', exerciseId: 'exercise', source: 'MANUAL', baseRevision: '1', resultRevision: '2', taskId: null, conversationId: null, originChangeId: null, retained: true, recordedAt: '2026-01-01T00:00:00Z' };
let view: VueWrapper | undefined;
let client: QueryClient;
function panel() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  view = mount(WorkspaceHistory, { props: { owner: 'owner', exercise, credential: 'credential', disabled: false }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { WorkspaceFileDiff: true } } });
  return view;
}
function button(text: string) {
  const found = view!.findAll('button').find(item => item.text() === text);
  if (!found) throw new Error(`Missing ${text}`);
  return found;
}
async function inspect() {
  await flushPromises(); await view!.get('select').setValue('change');
  await button('预览撤销此次修改').trigger('click'); await flushPromises();
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.history).mockResolvedValue({ exerciseId: 'exercise', changes: [change], nextBefore: null });
  vi.mocked(api.preview).mockImplementation(async (_owner, _exercise, selected, input) => ({ ...input, exerciseId: 'exercise', changeId: selected.id, fingerprint: 'fingerprint', conflicts: [], changes: [] }));
});
afterEach(() => { view?.unmount(); client?.clear(); });
it('opens report-referenced changes as a frozen read-only Diff without restoring or replaying tools', async () => {
  panel(); await flushPromises();
  await view!.setProps({ initialChange: 'change', exercise: { ...exercise, state: 'ENDED' }, credential: '', disabled: true });
  await flushPromises();
  expect(api.preview).toHaveBeenCalledWith('owner', 'exercise', change, { mode: 'DIFF', targetRevision: null, expectedRevision: '2' }, expect.any(AbortSignal));
  expect(api.restore).not.toHaveBeenCalled();
  expect(view!.get('select').element.value).toBe('change');
});
it('reads history without writes, binds explicit preview to revision, and rejects stale confirmation', async () => {
  panel(); await inspect();
  expect(api.restore).not.toHaveBeenCalled();
  await view!.get('input[type=checkbox]').setValue(true);
  expect(button('确认恢复为新修订').attributes('disabled')).toBeUndefined();
  await view!.setProps({ exercise: { ...exercise, revision: '3' } });
  expect(button('确认恢复为新修订').attributes('disabled')).toBeDefined();
  expect(view!.text()).toContain('当前修订已变化');
  expect(api.preview).toHaveBeenCalledTimes(1);
});
it('keeps unknown original intent across credential rotation and raw conflict, resolving only explicit proof', async () => {
  vi.mocked(api.restore).mockRejectedValueOnce(new TypeError('offline')).mockRejectedValueOnce(new ApiError(409, 'REVISION_CONFLICT')).mockResolvedValueOnce({ notAccepted: true, reason: 'REVISION_CONFLICT' });
  panel(); await inspect(); await view!.get('input[type=checkbox]').setValue(true);
  await button('确认恢复为新修订').trigger('click'); await flushPromises();
  await view!.setProps({ credential: 'rotated', exercise: { ...exercise, revision: '3' } });
  await button('核对原恢复请求，不更换意图').trigger('click'); await flushPromises();
  await button('核对原恢复请求，不更换意图').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.restore).mock.calls;
  expect(calls).toHaveLength(3);
  expect(calls[2]!.slice(0, 4)).toEqual(calls[0]!.slice(0, 4));
  expect(calls[2]![4]).toBe('rotated');
  expect(view!.text()).toContain('已核对原请求未受理');
});
it('rejects undo conflicts and frozen writes while allowing inspection', async () => {
  vi.mocked(api.preview).mockResolvedValue({ exerciseId: 'exercise', changeId: 'change', mode: 'UNDO', expectedRevision: '2', targetRevision: null, fingerprint: 'fingerprint', changes: [], conflicts: ['src/main/java/Main.java'] });
  panel(); await inspect();
  expect(view!.text()).toContain('撤销冲突');
  expect(button('确认恢复为新修订').attributes('disabled')).toBeDefined();
  await view!.setProps({ exercise: { ...exercise, state: 'ENDED' } });
  expect(button('查看修改 Diff').attributes('disabled')).toBeUndefined();
  expect(api.restore).not.toHaveBeenCalled();
});
it('discards late restore results after changing owner', async () => {
  let complete: (value: api.RestoreOutcome) => void = () => {};
  vi.mocked(api.restore).mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  panel(); await inspect(); await view!.get('input[type=checkbox]').setValue(true);
  await button('确认恢复为新修订').trigger('click');
  await view!.setProps({ owner: 'other' });
  complete({ notAccepted: true, reason: 'REVISION_CONFLICT' }); await flushPromises();
  expect(view!.text()).not.toContain('已核对原请求未受理');
  expect(view!.emitted('changed')).toBeUndefined();
});
