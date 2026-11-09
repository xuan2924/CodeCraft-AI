import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import WorkspaceEditor from './WorkspaceEditor.vue';
import * as api from './api';
import * as drafts from './drafts';
import { ApiError } from '../api/request';

const navigation = vi.hoisted(() => ({ guard: (): boolean | Promise<boolean> => true }));
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (guard: () => boolean | Promise<boolean>) => { navigation.guard = guard; } }));
vi.mock('./CodeEditor.vue', () => ({ default: { props: ['path', 'modelValue', 'readonly'], emits: ['update:modelValue', 'save'],
  template: '<textarea aria-label="测试编辑器" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' } }));
vi.mock('./drafts', () => ({ readDraftEpoch: vi.fn(async () => 0), listDrafts: vi.fn(async () => []), putDraft: vi.fn(async () => {}), removeDraft: vi.fn(async () => {}) }));
vi.mock('./api', async (original) => ({ ...await original<typeof api>(), save: vi.fn(), operation: vi.fn() }));
const file = { workspaceInstanceId: 'instance', path: 'src/main/java/Main.java', revision: '0', content: 'base' };
const exercise: api.Exercise = { exerciseId: 'exercise', problemVersionId: 'problem', workspaceInstanceId: 'instance', state: 'ACTIVE', revision: '0',
  paths: [file.path], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
let view: VueWrapper | undefined;
beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }); });
afterEach(async () => { view?.unmount(); view = undefined; await flushPromises(); vi.useRealTimers(); vi.clearAllMocks(); });
function render() {
  view = mount(WorkspaceEditor, { props: { owner: 'owner', exercise, file, selected: file.path, credential: 'credential' } });
  return view;
}
it('retains newer input when a save response arrives and backs it up with the new baseline', async () => {
  let finish!: (value: api.Mutation) => void;
  vi.mocked(api.save).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('submitted');
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('submitted plus typing');
  finish({ exerciseId: 'exercise', operationId: 'operation', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  await flushPromises();
  expect(page.get('textarea').element.value).toBe('submitted plus typing');
  expect(page.text()).toContain('保存期间的新输入仍未保存');
  await vi.advanceTimersByTimeAsync(350);
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ owner: 'owner', exercise: 'exercise', revision: '1', baseContent: 'submitted', content: 'submitted plus typing' }), 0);
});
it('restores a conflicting draft only into the editor without writing the server', async () => {
  vi.mocked(drafts.listDrafts).mockResolvedValueOnce([{ owner: 'owner', exercise: 'exercise', path: file.path, id: 'older-page', revision: '0', baseContent: 'base', content: 'local draft', updatedAt: 1 }]);
  const page = render();
  await page.setProps({ file: { ...file, revision: '1', content: 'server changed' } });
  await flushPromises();
  await page.findAll('button').find((button) => button.text() === '恢复此草稿')!.trigger('click');
  expect(page.get('textarea').element.value).toBe('local draft');
  expect(page.text()).toContain('server changed');
  expect(page.text()).toContain('尚未写入服务器');
  expect(api.save).not.toHaveBeenCalled();
});
it('reports storage failures and ignores a save response after disposal', async () => {
  vi.mocked(drafts.putDraft).mockRejectedValueOnce(new Error('quota fixture'));
  let finish!: (value: api.Mutation) => void;
  vi.mocked(api.save).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('local');
  await vi.advanceTimersByTimeAsync(350);
  expect(page.text()).toContain('本地草稿更新失败');
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  page.unmount(); view = undefined;
  finish({ exerciseId: 'exercise', operationId: 'operation', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  await flushPromises();
  expect(page.emitted('saved')).toBeUndefined();
});
it('locks the affected editor while deletion is unresolved so new typing cannot be discarded by its receipt', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  vi.mocked(api.save).mockImplementationOnce(() => new Promise(() => {}));
  try {
    const page = render(); await flushPromises();
    await page.findAll('button').find((button) => button.text() === '删除文件')!.trigger('click');
    await flushPromises();
    expect(page.get('textarea').element.readOnly).toBe(true);
  } finally { confirmation.mockRestore(); }
});
it('cancels the scheduled query when manual reconciliation already confirmed the result', async () => {
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'operation', state: 'PENDING', revision: null, changed: null, failureCode: null });
  vi.mocked(api.operation).mockResolvedValue({ exerciseId: 'exercise', operationId: 'operation', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('submitted');
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  await page.findAll('button').find((button) => button.text() === '核对原保存')!.trigger('click');
  await flushPromises();
  await vi.advanceTimersByTimeAsync(1100);
  expect(api.operation).toHaveBeenCalledTimes(1);
  expect(page.emitted('saved')).toHaveLength(1);
});
it('keeps the page open when typing continues during save then leave', async () => {
  let finish!: (value: api.Mutation) => void;
  vi.mocked(api.save).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('submitted');
  const departure = navigation.guard(); await flushPromises();
  await page.findAll('button').find((button) => button.text() === '保存后离开')!.trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('newer input');
  finish({ exerciseId: 'exercise', operationId: 'operation', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  await flushPromises();
  expect(page.find('[role="dialog"]').exists()).toBe(true);
  expect(page.get('textarea').element.value).toBe('newer input');
  await page.findAll('button').find((button) => button.text() === '取消离开')!.trigger('click');
  expect(await departure).toBe(false);
});
it('preserves input entered while discard waits for local storage', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  let finish!: () => void;
  vi.mocked(drafts.putDraft).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  try {
    const page = render(); await flushPromises();
    await page.get('textarea').setValue('confirmed discard');
    await page.findAll('button').find((button) => button.text() === '丢弃当前草稿')!.trigger('click');
    await flushPromises();
    await page.get('textarea').setValue('new input after confirmation');
    finish(); await flushPromises();
    expect(page.get('textarea').element.value).toBe('new input after confirmation');
    expect(page.text()).toContain('丢弃期间产生了新输入');
  } finally { confirmation.mockRestore(); }
});

it('does not leave when input changes while discard waits for local storage', async () => {
  let finish!: () => void;
  vi.mocked(drafts.putDraft).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('confirmed discard');
  const departure = navigation.guard(); await flushPromises();
  await page.findAll('button').find((button) => button.text() === '丢弃并离开')!.trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('new input after confirmation');
  finish(); await flushPromises();
  expect(page.get('textarea').element.value).toBe('new input after confirmation');
  expect(page.find('[role="dialog"]').exists()).toBe(true);
  await page.findAll('button').find((button) => button.text() === '取消离开')!.trigger('click');
  expect(await departure).toBe(false);
});

it('removes its obsolete backup when editing returns to the baseline', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('obsolete local draft');
  await vi.advanceTimersByTimeAsync(350);
  const savedDraft = vi.mocked(drafts.putDraft).mock.calls[0]![0];
  await page.get('textarea').setValue('base');
  await vi.advanceTimersByTimeAsync(350);
  expect(drafts.removeDraft).toHaveBeenCalledWith(savedDraft);
  expect(page.get('textarea').element.value).toBe('base');
});

it('keeps drafts and the route open when save before leaving fails', async () => {
  vi.mocked(api.save).mockRejectedValueOnce(new ApiError(409, 'REVISION_CONFLICT'));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('keep this draft');
  const departure = navigation.guard(); await flushPromises();
  await page.findAll('button').find((button) => button.text() === '保存后离开')!.trigger('click');
  await flushPromises();
  expect(page.get('textarea').element.value).toBe('keep this draft');
  expect(page.find('[role="dialog"]').exists()).toBe(true);
  expect(drafts.removeDraft).not.toHaveBeenCalled();
  await page.findAll('button').find((button) => button.text() === '取消离开')!.trigger('click');
  expect(await departure).toBe(false);
});

it('guards refresh for dirty or unresolved changes and releases the listener on disposal', async () => {
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'operation', state: 'PENDING', revision: null, changed: null, failureCode: null });
  const page = render(); await flushPromises();
  const refresh = () => {
    const event = new Event('beforeunload', { cancelable: true });
    globalThis.dispatchEvent(event);
    return event.defaultPrevented;
  };
  expect(refresh()).toBe(false);
  await page.get('textarea').setValue('local');
  expect(refresh()).toBe(true);
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('base');
  expect(refresh()).toBe(true);
  page.unmount(); view = undefined;
  expect(refresh()).toBe(false);
});

it('does not erase another page draft when discarding a recovered local copy', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  vi.mocked(drafts.listDrafts).mockResolvedValueOnce([{ owner: 'owner', exercise: 'exercise', path: file.path, id: 'other-page', revision: '0', baseContent: 'base', content: 'local draft', updatedAt: 1 }]);
  try {
    const page = render(); await flushPromises();
    await page.findAll('button').find((button) => button.text() === '恢复此草稿')!.trigger('click');
    await page.findAll('button').find((button) => button.text() === '丢弃当前草稿')!.trigger('click');
    await flushPromises();
    expect(vi.mocked(drafts.removeDraft).mock.calls.some(([draft]) => draft.id === 'other-page')).toBe(false);
  } finally { confirmation.mockRestore(); }
});
