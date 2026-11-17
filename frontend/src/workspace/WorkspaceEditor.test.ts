import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import WorkspaceEditor from './WorkspaceEditor.vue';
import ClosedDrafts from './ClosedDrafts.vue';
import * as api from './api';
import * as drafts from './drafts';
import { ApiError } from '../api/request';

const navigation = vi.hoisted(() => ({ guard: (): boolean | Promise<boolean> => true }));
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (guard: () => boolean | Promise<boolean>) => { navigation.guard = guard; } }));
vi.mock('./CodeEditor.vue', () => ({ default: { props: ['path', 'modelValue', 'readonly'], emits: ['update:modelValue', 'save'],
  template: '<textarea aria-label="测试编辑器" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' } }));
vi.mock('./drafts', () => ({ readDraftEpoch: vi.fn(async () => 0), listDrafts: vi.fn(async () => []), listAccountDrafts: vi.fn(async () => []), putDraft: vi.fn(async () => {}), removeDraft: vi.fn(async () => {}) }));
vi.mock('./api', async (original) => ({ ...await original<typeof api>(), save: vi.fn(), operation: vi.fn() }));
const file = { workspaceInstanceId: 'instance', path: 'src/main/java/Main.java', revision: '0', content: 'base' };
const exercise: api.Exercise = { exerciseId: 'exercise', problemVersionId: 'problem', workspaceInstanceId: 'instance', state: 'ACTIVE', revision: '0',
  paths: [file.path], deadlineAt: null, preparationFailureReason: null, cleanupState: null };
let view: VueWrapper | undefined;
beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }); });
afterEach(async () => { view?.unmount(); view = undefined; await flushPromises(); vi.useRealTimers(); vi.clearAllMocks(); });
function render(owner = 'owner') {
  view = mount(WorkspaceEditor, { props: { owner, exercise, file, selected: file.path, credential: 'credential' } });
  return view;
}
it('labels the server-confirmed challenge target without changing saved revision or saving implicitly', async () => {
  const page = render(); await flushPromises();
  await page.setProps({ testTarget: 'challenge' });
  await page.findAll('button').find(button => button.text() === '运行挑战与基础回归')!.trigger('click');
  expect(page.emitted('run-test')).toContainEqual(['0']);
  expect(api.save).not.toHaveBeenCalled();
  expect(page.get('textarea').element.value).toBe('base');
});
it('removes a clean buffer when a newer workspace revision deletes its file', async () => {
  const page = render(); await flushPromises();
  await page.setProps({ exercise: { ...exercise, revision: '1', paths: [] }, file: undefined });
  expect(page.get('textarea').element.value).toBe('');
  expect(page.get('textarea').attributes('readonly')).toBeDefined();
  expect(page.emitted('select')).toContainEqual(['']);
  expect(api.save).not.toHaveBeenCalled();
});

it('retains a deleted-file draft with an explicit conflict and rejects stale file responses', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('draft during Bash');
  await page.setProps({ exercise: { ...exercise, revision: '1', paths: [] }, file: undefined });
  expect(page.text()).toContain('正式文件已在修订 1 删除');
  expect(page.get('textarea').element.value).toBe('draft during Bash');
  await page.setProps({ file: { ...file } });
  expect(page.text()).toContain('正式文件已在修订 1 删除');
  await page.findAll('button').find(button => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  expect(api.save).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(350);
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ content: 'draft during Bash' }), 0);
});

it('makes deleted-file backups readable after reloading an active workspace without recreating files', async () => {
  vi.mocked(drafts.listAccountDrafts).mockResolvedValueOnce([{ owner: 'owner', exercise: 'exercise', id: 'old-page',
    path: file.path, content: 'deleted file backup', baseContent: 'base', revision: '0', updatedAt: 1 }]);
  view = mount(WorkspaceEditor, { props: { owner: 'owner', exercise: { ...exercise, revision: '1', paths: [] }, selected: '', credential: 'credential' } });
  await flushPromises();
  expect(view.get('[aria-label="本场本地草稿备份"]').text()).toContain('deleted file backup');
  expect(api.save).not.toHaveBeenCalled();
  expect(drafts.removeDraft).not.toHaveBeenCalled();
});

it('keeps the editable draft and backup after unified storage admission is denied', async () => {
  vi.mocked(api.save).mockRejectedValueOnce(new ApiError(429, 'STORAGE_BUDGET_EXCEEDED'));
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('keep on quota denial');
  await vi.advanceTimersByTimeAsync(350);
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  expect(page.get('textarea').element.value).toBe('keep on quota denial');
  expect(page.text()).toContain('单场数据容量不足');
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ content: 'keep on quota denial' }), 0);
  expect(drafts.removeDraft).not.toHaveBeenCalled();
  expect(api.operation).not.toHaveBeenCalled();
});

it('backs up but never submits or deletes dirty content for owner recovery ending without page control', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('retain on recovery end');
  await page.setProps({ credential: '' });
  expect(await page.vm.$.exposed?.prepareEnd('retain')).toBe(true);
  expect(page.get('textarea').element.value).toBe('retain on recovery end');
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ content: 'retain on recovery end' }), 0);
  expect(api.save).not.toHaveBeenCalled(); expect(drafts.removeDraft).not.toHaveBeenCalled();
});

it('retains local drafts read-only at expiry without submitting or deleting them', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('keep after expiry');
  await page.setProps({ answeringClosed: true });
  await vi.advanceTimersByTimeAsync(350);
  expect(page.get('textarea').attributes('readonly')).toBeDefined();
  expect(page.get('textarea').element.value).toBe('keep after expiry');
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ content: 'keep after expiry' }), 0);
  expect(api.save).not.toHaveBeenCalled();
  expect(drafts.removeDraft).not.toHaveBeenCalled();
  expect(await page.vm.$.exposed?.prepareEnd('save')).toBe(false);
});

it('makes backed up drafts viewable after an expired editor is unmounted and the ended page is reloaded', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('retained across page reload');
  await vi.advanceTimersByTimeAsync(350);
  const saved = vi.mocked(drafts.putDraft).mock.calls.at(-1)?.[0];
  expect(saved).toBeDefined();
  await page.setProps({ answeringClosed: true });
  page.unmount();
  await flushPromises();
  vi.mocked(drafts.listAccountDrafts).mockResolvedValueOnce([saved!]);
  view = mount(ClosedDrafts, { props: { owner: 'owner', exerciseId: 'exercise' } });
  await flushPromises();
  expect(view.get('pre').text()).toBe('retained across page reload');
  expect(drafts.removeDraft).not.toHaveBeenCalled();
  expect(api.save).not.toHaveBeenCalled();
});

it('prepares explicit discard for ending without changing the committed workspace', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('discard me');
  expect(await page.vm.$.exposed?.prepareEnd('discard')).toBe(true);
  expect(page.get('textarea').element.value).toBe('base');
  expect(api.save).not.toHaveBeenCalled();
  expect(drafts.removeDraft).toHaveBeenCalledWith(expect.objectContaining({ content: 'discard me' }));
});

it('requires save confirmation before emitting a test start for the confirmed revision', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('changed for test');
  await page.findAll('button').find((button) => button.text() === '运行基础测试')!.trigger('click');
  expect(page.emitted('run-test')).toBeUndefined();
  expect(page.text()).toContain('有未保存修改，测试不会使用这些草稿');
  await page.findAll('button').find((button) => button.text() === '返回编辑')!.trigger('click');
  expect(api.save).not.toHaveBeenCalled();
  await page.findAll('button').find((button) => button.text() === '运行基础测试')!.trigger('click');
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'save-test', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  await page.findAll('button').find((button) => button.text() === '保存后运行')!.trigger('click');
  await flushPromises();
  expect(page.emitted('run-test')).toEqual([['1']]);
});

it('does not start a test after a failed save or when typing continues during save', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('draft');
  await page.findAll('button').find((button) => button.text() === '运行基础测试')!.trigger('click');
  vi.mocked(api.save).mockRejectedValueOnce(new ApiError(409, 'REVISION_CONFLICT'));
  await page.findAll('button').find((button) => button.text() === '保存后运行')!.trigger('click');
  await flushPromises();
  expect(page.emitted('run-test')).toBeUndefined();
  let finish!: (value: api.Mutation) => void;
  vi.mocked(api.save).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  await page.findAll('button').find((button) => button.text() === '保存后运行')!.trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('newer draft');
  finish({ exerciseId: 'exercise', operationId: 'save-test', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  await flushPromises();
  expect(page.emitted('run-test')).toBeUndefined();
  expect(page.get('textarea').element.value).toBe('newer draft');
  expect(page.text()).toContain('未启动测试');
});

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
it('warns before closing a dirty file and retains its buffer when reopened', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
  try {
    const page = render(); await flushPromises();
    await page.get('textarea').setValue('draft survives close');
    const close = page.findAll('button').find((button) => button.text() === '关闭文件');
    expect(close).toBeDefined();
    await close!.trigger('click');
    expect(page.emitted('select')).toBeUndefined();
    await close!.trigger('click');
    expect(page.emitted('select')).toEqual([['']]);
    await page.setProps({ selected: '', file: undefined });
    await page.setProps({ selected: file.path, file });
    expect(page.get('textarea').element.value).toBe('draft survives close');
    expect(drafts.removeDraft).not.toHaveBeenCalled();
  } finally { confirmation.mockRestore(); }
});

it('retains each file buffer and draft while switching files', async () => {
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('first file draft');
  const second = { ...file, path: 'src/main/java/Second.java', content: 'second baseline' };
  await page.setProps({ selected: second.path, file: second, exercise: { ...exercise, paths: [file.path, second.path] } });
  await flushPromises();
  await page.get('textarea').setValue('second file draft');
  await vi.advanceTimersByTimeAsync(350);
  await page.setProps({ selected: file.path, file });
  await flushPromises();
  expect(page.get('textarea').element.value).toBe('first file draft');
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ path: file.path, content: 'first file draft' }), 0);
  expect(drafts.putDraft).toHaveBeenCalledWith(expect.objectContaining({ path: second.path, content: 'second file draft' }), 0);
  expect(api.save).not.toHaveBeenCalled();
});

it('requires another explicit baseline adoption when the server changes again', async () => {
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'save-operation', state: 'SUCCEEDED', revision: '3', changed: true, failureCode: null });
  const page = render(); await flushPromises();
  await page.get('textarea').setValue('local work');
  await page.setProps({ file: { ...file, revision: '1', content: 'server version one' } });
  await flushPromises();
  expect(page.get('[aria-label="当前服务端内容"]').text()).toContain('server version one');
  await page.findAll('button').find((button) => button.text() === '保留本地内容，采用此基线')!.trigger('click');
  await page.setProps({ file: { ...file, revision: '2', content: 'server version two' } });
  await flushPromises();
  expect(page.get('[aria-label="当前服务端内容"]').text()).toContain('server version two');
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  expect(api.save).not.toHaveBeenCalled();
  expect(page.get('textarea').element.value).toBe('local work');
  await page.findAll('button').find((button) => button.text() === '保留本地内容，采用此基线')!.trigger('click');
  await page.findAll('button').find((button) => button.text() === '保存文件')!.trigger('click');
  await flushPromises();
  expect(api.save).toHaveBeenCalledWith('exercise', '2', [{ path: file.path, kind: 'UPDATE', content: 'local work' }], expect.any(String), 'credential', expect.any(AbortSignal));
  expect(page.text()).toContain('已保存到工作区，修订 3');
});

it('keeps late draft reads and backups scoped to the old account after remount', async () => {
  let finishRead!: (values: drafts.Draft[]) => void;
  let finishBackup!: () => void;
  vi.mocked(drafts.listDrafts).mockImplementationOnce(() => new Promise((resolve) => { finishRead = resolve; }));
  vi.mocked(drafts.putDraft).mockImplementationOnce(() => new Promise((resolve) => { finishBackup = resolve; }));
  const oldPage = render('old-owner'); await flushPromises();
  await oldPage.get('textarea').setValue('old private text');
  await vi.advanceTimersByTimeAsync(350);
  oldPage.unmount(); view = undefined;
  const newPage = render('new-owner'); await flushPromises();
  await newPage.get('textarea').setValue('new account text');
  finishRead([{ owner: 'old-owner', exercise: 'exercise', path: file.path, id: 'old-page', revision: '0', baseContent: 'base', content: 'old private backup', updatedAt: 1 }]);
  finishBackup(); await flushPromises();
  await vi.advanceTimersByTimeAsync(350);
  expect(newPage.get('textarea').element.value).toBe('new account text');
  expect(newPage.text()).not.toContain('old private');
  expect(newPage.findAll('button').some((button) => button.text() === '恢复此草稿')).toBe(false);
  const backups = vi.mocked(drafts.putDraft).mock.calls.map(([draft]) => draft);
  expect(backups.some((draft) => draft.owner === 'new-owner' && draft.content === 'new account text')).toBe(true);
  expect(backups.filter((draft) => draft.content === 'old private text').every((draft) => draft.owner === 'old-owner')).toBe(true);
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
    await page.get('button[aria-label="管理文件"]').trigger('click');
    await page.findAll('button').find((button) => button.text() === '删除文件')!.trigger('click');
    await flushPromises();
    expect(page.get('textarea').element.readOnly).toBe(true);
  } finally { confirmation.mockRestore(); }
});
it('locks a pending rename until its original receipt confirms the new path', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  vi.mocked(drafts.listDrafts).mockResolvedValueOnce([{ owner: 'owner', exercise: 'exercise', path: file.path, id: 'other-page', revision: '0', baseContent: 'base', content: 'recoverable draft', updatedAt: 1 }]);
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'rename-operation', state: 'PENDING', revision: null, changed: null, failureCode: null });
  vi.mocked(api.operation).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'rename-operation', state: 'SUCCEEDED', revision: '1', changed: true, failureCode: null });
  try {
    const page = render(); await flushPromises();
    await page.get('button[aria-label="管理文件"]').trigger('click');
    await page.get('#file-destination').setValue('src/main/java/Renamed.java');
    await page.findAll('button').find((button) => button.text() === '重命名文件')!.trigger('click');
    await flushPromises();
    expect(page.get('textarea').element.readOnly).toBe(true);
    expect(page.get('textarea').element.value).toBe('base');
    expect(page.findAll('button').find((button) => button.text() === '恢复此草稿')!.attributes('disabled')).toBeDefined();
    expect(page.emitted('select')).toBeUndefined();
    expect(drafts.removeDraft).not.toHaveBeenCalled();
    await page.findAll('button').find((button) => button.text() === '核对原保存')!.trigger('click');
    await flushPromises();
    expect(api.save).toHaveBeenCalledTimes(1);
    expect(api.operation).toHaveBeenCalledWith('rename-operation', expect.any(AbortSignal));
    expect(page.emitted('select')).toEqual([['src/main/java/Renamed.java']]);
    expect(page.text()).toContain('已保存到工作区，修订 1');
  } finally { confirmation.mockRestore(); }
});

it('preserves the source buffer and unlocks it after a rejected rename receipt', async () => {
  const confirmation = vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  vi.mocked(api.save).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'rename-operation', state: 'PENDING', revision: null, changed: null, failureCode: null });
  vi.mocked(api.operation).mockResolvedValueOnce({ exerciseId: 'exercise', operationId: 'rename-operation', state: 'REJECTED', revision: null, changed: null, failureCode: 'FILE_EXISTS' });
  try {
    const page = render(); await flushPromises();
    await page.get('button[aria-label="管理文件"]').trigger('click');
    await page.get('#file-destination').setValue('src/main/java/Existing.java');
    await page.findAll('button').find((button) => button.text() === '重命名文件')!.trigger('click');
    await flushPromises();
    expect(page.get('textarea').element.readOnly).toBe(true);
    await page.findAll('button').find((button) => button.text() === '核对原保存')!.trigger('click');
    await flushPromises();
    expect(page.get('textarea').element.readOnly).toBe(false);
    expect(page.get('textarea').element.value).toBe('base');
    expect(page.emitted('select')).toBeUndefined();
    expect(drafts.removeDraft).not.toHaveBeenCalled();
    expect(api.save).toHaveBeenCalledTimes(1);
    expect(page.get('[role="alert"]').text()).toContain('已存在');
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
