import { flushPromises, mount, type ComponentMountingOptions } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { beforeEach, expect, it, vi } from 'vitest';
import FeedbackComposer from './FeedbackComposer.vue';
import FeedbackEntry from './FeedbackEntry.vue';
import * as api from './feedbackApi';
vi.mock('./feedbackApi', () => ({ preview: vi.fn(), submit: vi.fn(), reply: vi.fn() }));
const props = { owner: 'owner', exerciseId: 'exercise', targetType: 'REPORT' as const, targetId: 'report', generation: 2, choices: [{ id: 'source', summary: '明确选择的源码片段' }] };
const global = (): ComponentMountingOptions<typeof FeedbackComposer>['global'] => ({ plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] });
const value: api.Feedback = { id: 'feedback', snapshot: '{"description":"仅授权内容"}', state: 'PENDING', reply: null, createdAt: '2026-01-01T00:00:00Z', handledAt: null };
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.preview).mockResolvedValue({ snapshot: '{"evidence":[],"description":"<script>原样预览</script>"}', fingerprint: 'fingerprint' }); });
it('defaults to no attachments, requires complete preview and resets authorization after edits', async () => {
  const wrapper = mount(FeedbackComposer, { props, global: global() });
  await wrapper.get('textarea').setValue('请核对');
  await wrapper.findAll('button').find(item => item.text() === '预览实际发送范围')!.trigger('click'); await flushPromises();
  expect(api.preview).toHaveBeenCalledWith('owner', 'exercise', expect.objectContaining({ evidenceIds: [], generation: 2 }), expect.any(AbortSignal));
  expect(wrapper.get('pre').text()).toContain('<script>原样预览</script>'); expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.findAll('button').find(item => item.text() === '确认授权并提交')!.attributes('disabled')).toBeDefined();
  await wrapper.findAll('input[type="checkbox"]').at(-1)!.setValue(true);
  await wrapper.get('textarea').setValue('改变了描述');
  expect(wrapper.find('pre').exists()).toBe(false); expect(api.submit).not.toHaveBeenCalled(); wrapper.unmount();
});
it('explicit evidence and consent are sent once; unknown delivery retries the same payload and key', async () => {
  vi.mocked(api.submit).mockRejectedValue(new Error('network'));
  const wrapper = mount(FeedbackComposer, { props, global: global() });
  await wrapper.get('textarea').setValue('请核对'); await wrapper.get('input[type="checkbox"]').setValue(true);
  await wrapper.findAll('button').find(item => item.text() === '预览实际发送范围')!.trigger('click'); await flushPromises();
  await wrapper.findAll('input[type="checkbox"]').at(-1)!.setValue(true);
  await wrapper.findAll('button').find(item => item.text() === '确认授权并提交')!.trigger('click'); await flushPromises();
  expect(api.submit).toHaveBeenCalledWith('owner', 'exercise', expect.objectContaining({ evidenceIds: ['source'], previewFingerprint: 'fingerprint' }), expect.any(String), expect.any(AbortSignal));
  await wrapper.findAll('button').find(item => item.text() === '核对原反馈请求')!.trigger('click'); await flushPromises();
  expect(vi.mocked(api.submit).mock.calls[1]?.slice(0, 4)).toEqual(vi.mocked(api.submit).mock.calls[0]?.slice(0, 4)); wrapper.unmount();
});
it('preview errors never become an empty authorized snapshot', async () => {
  vi.mocked(api.preview).mockRejectedValue(new Error('deleted'));
  const wrapper = mount(FeedbackComposer, { props, global: global() });
  await wrapper.get('textarea').setValue('请核对'); await wrapper.get('button').trigger('click'); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(true); expect(wrapper.find('pre').exists()).toBe(false); wrapper.unmount();
});
it('only administrator presentation offers a one-shot reply, without private workspace links', async () => {
  vi.mocked(api.reply).mockResolvedValue({ ...value, state: 'HANDLED', reply: '已核对' });
  const wrapper = mount(FeedbackEntry, { props: { owner: 'admin', admin: true, value }, global: global() });
  expect(wrapper.find('a').exists()).toBe(false);
  await wrapper.get('textarea').setValue('已核对'); await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(api.reply).toHaveBeenCalledWith('admin', 'feedback', '已核对', expect.any(String), expect.any(AbortSignal));
  await wrapper.setProps({ admin: false }); expect(wrapper.find('form').exists()).toBe(false); wrapper.unmount();
});
