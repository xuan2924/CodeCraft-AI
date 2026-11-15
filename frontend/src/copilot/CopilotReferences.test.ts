import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import CopilotReferences from './CopilotReferences.vue';
import { preview, sources, type ReferencePage } from './referenceApi';
import { ApiError } from '../api/request';
vi.mock('./referenceApi', () => ({ preview: vi.fn(), sources: vi.fn() }));
const page: ReferencePage = { kind: 'FILE', source: 'src/Main.java', version: 'workspace:1', offset: 0, limit: 2048, fingerprint: 'a'.repeat(64), text: 'a'.repeat(2048), nextOffset: 2048, retainedBytes: 5000, sourceIncomplete: false, droppedBytes: '0' };
const views: ReturnType<typeof mount>[] = [];
function panel() { const view = mount(CopilotReferences, { props: { owner: 'owner', exerciseId: 'exercise', conversationId: 'conversation', disabled: false, paths: ['src/Main.java'], modelValue: [] } }); views.push(view); return view; }
function button(view: ReturnType<typeof mount>, label: string) { return view.findAll('button').find(item => item.text() === label)!; }
afterEach(() => { views.splice(0).forEach(view => view.unmount()); vi.resetAllMocks(); vi.unstubAllGlobals(); });
it('previews and selects exact saved code bytes, retains immutable preview and allows removal', async () => {
  vi.mocked(preview).mockResolvedValueOnce(page).mockResolvedValueOnce({ ...page, kind: 'CODE', offset: 3, limit: 4, text: 'aaaa', nextOffset: 7, fingerprint: 'b'.repeat(64) });
  const view = panel(); await view.findAll('select')[1]!.setValue(page.source); await button(view, '预览当前来源').trigger('click'); await flushPromises();
  const text = view.get('textarea'); text.element.setSelectionRange(3, 7); await text.trigger('select');
  await button(view, '引用选中代码／日志').trigger('click'); await flushPromises();
  expect(preview).toHaveBeenLastCalledWith('owner', 'exercise', 'conversation', 'CODE', page.source, 3, 4, page.version, expect.any(AbortSignal));
  const added = view.emitted('update:modelValue')![0]![0] as ReferencePage[];
  expect(added[0]).toMatchObject({ text: 'aaaa', offset: 3, kind: 'CODE' }); await view.setProps({ modelValue: added });
  expect(view.text()).toContain('CODE · src/Main.java'); await button(view, '移除此引用').trigger('click'); expect(view.emitted('update:modelValue')!.at(-1)).toEqual([[]]);
});
it.each([
  { text: 'a\r\nbc', start: 2, end: 3, offset: 3, selected: 'b' },
  { text: '中\r\n文', start: 2, end: 3, offset: 5, selected: '文' },
  { text: 'a\r\nbc', start: 1, end: 3, offset: 1, selected: '\r\nb' },
])('maps normalized textarea boundaries back to original CRLF bytes: $offset/$selected', async ({ text, start, end, offset, selected }) => {
  const limit = new TextEncoder().encode(selected).length;
  vi.mocked(preview).mockResolvedValueOnce({ ...page, text, retainedBytes: new TextEncoder().encode(text).length, nextOffset: null })
    .mockResolvedValueOnce({ ...page, kind: 'CODE', offset, limit, text: selected, fingerprint: 'c'.repeat(64) });
  const view = panel(); await view.findAll('select')[1]!.setValue(page.source); await button(view, '预览当前来源').trigger('click'); await flushPromises();
  const textarea = view.get('textarea'); expect(textarea.element.value).toBe(text.replace(/\r\n/g, '\n'));
  textarea.element.setSelectionRange(start, end); await textarea.trigger('select'); await button(view, '引用选中代码／日志').trigger('click'); await flushPromises();
  expect(preview).toHaveBeenLastCalledWith('owner', 'exercise', 'conversation', 'CODE', page.source, offset, limit, page.version, expect.any(AbortSignal));
  expect(view.emitted('update:modelValue')![0]![0]).toEqual([expect.objectContaining({ text: selected, offset, limit })]);
});
it('separates retained loss from display paging and keeps source versions on continuation', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  vi.mocked(preview).mockResolvedValue({ ...page, sourceIncomplete: true, droppedBytes: '128' });
  const view = panel(); await view.findAll('select')[1]!.setValue(page.source); await button(view, '预览当前来源').trigger('click'); await flushPromises();
  expect(view.text()).toContain('已丢弃 128 字节'); expect(view.text()).toContain('还有保留内容可读取');
  await button(view, '复制当前来源片段').trigger('click'); await flushPromises(); expect(view.text()).toContain('复制失败');
  vi.mocked(preview).mockRejectedValue(new ApiError(409, 'COPILOT_REFERENCE_CHANGED'));
  await button(view, '继续读取保留来源').trigger('click'); await flushPromises();
  expect(preview).toHaveBeenLastCalledWith('owner', 'exercise', 'conversation', 'FILE', page.source, 2048, 2048, page.version, expect.any(AbortSignal));
  expect(view.text()).toContain('未用新内容替换旧引用'); expect(view.emitted('update:modelValue')).toBeUndefined();
});
it('selects an existing command log identity without rerunning the command', async () => {
  const id = '10000000-0000-0000-0000-000000000001';
  vi.mocked(sources).mockResolvedValue({ sources: [{ id, label: 'printf original', state: 'SUCCEEDED', revision: '1' }], nextCursor: null });
  vi.mocked(preview).mockResolvedValue({ ...page, kind: 'COMMAND_LOG', source: id });
  const view = panel(); await view.findAll('select')[0]!.setValue('COMMAND_LOG');
  await button(view, '选择已有日志任务').trigger('click'); await flushPromises();
  await view.findAll('select')[1]!.setValue(id); await button(view, '预览当前来源').trigger('click'); await flushPromises();
  expect(preview).toHaveBeenLastCalledWith('owner', 'exercise', 'conversation', 'COMMAND_LOG', id, 0, 2048, null, expect.any(AbortSignal));
  await button(view, '引用当前片段').trigger('click'); expect(view.emitted('update:modelValue')![0]![0]).toEqual([expect.objectContaining({ kind: 'COMMAND_LOG', source: id })]);
});
it('ignores an old conversation source response and renders source text without HTML', async () => {
  let finish: (value: ReferencePage) => void = () => {};
  vi.mocked(preview).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  const view = panel(); await view.findAll('select')[1]!.setValue(page.source); await button(view, '预览当前来源').trigger('click');
  await view.setProps({ conversationId: 'other' }); finish(page); await flushPromises(); expect(view.find('textarea').exists()).toBe(false);
  await view.setProps({ modelValue: [{ ...page, text: '<script>unsafe</script>' }] }); expect(view.find('script').exists()).toBe(false); expect(view.text()).toContain('<script>unsafe</script>');
});
