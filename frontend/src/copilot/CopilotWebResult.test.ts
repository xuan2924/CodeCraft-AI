import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import CopilotWebResult from './CopilotWebResult.vue';
const sources = [{ url: 'https://example.com/source', title: '正文', content: '<script>unsafe</script>' + 'a'.repeat(4096), retainedTruncated: true }];
function body(extra: Record<string, unknown> = {}) { return 'web_read [call]\n' + JSON.stringify({ code: 'OK', kind: 'FETCHED_BODY', input: 'https://example.com/source', sources, serviceCredits: '0.2', usageUnknown: false, ...extra }); }
afterEach(() => vi.unstubAllGlobals());
it('shows actual links, distinct retained loss and display pagination, and copies without refetching', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
  const copy = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText: copy } });
  const wrapper = mount(CopilotWebResult, { props: { text: body(), scope: 'task/message' } });
  expect(wrapper.text()).toContain('底层保留正文已截断'); expect(wrapper.text()).toContain('当前展示 1–2048');
  expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.get('a').attributes('href')).toBe(sources[0]!.url);
  const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)!;
  await button('继续查看保留资料').trigger('click'); expect(wrapper.text()).toContain('当前展示 2049–4096');
  await button('复制当前资料片段').trigger('click'); await flushPromises(); expect(copy).toHaveBeenCalledWith('a'.repeat(2048));
  expect(wrapper.text()).toContain('当前保留片段已复制');
  await button('上一段资料').trigger('click'); expect(wrapper.text()).not.toContain('当前保留片段已复制');
  expect(fetcher).not.toHaveBeenCalled(); wrapper.unmount();
});
it('rejects unsafe sources and displays unavailable or interrupted results without fake success', async () => {
  const wrapper = mount(CopilotWebResult, { props: { text: body({ sources: [{ ...sources[0], url: 'javascript:alert(1)' }] }), scope: 'task/message' } });
  expect(wrapper.find('a').exists()).toBe(false); expect(wrapper.text()).toContain('回执格式无效');
  await wrapper.setProps({ text: body({ code: 'WEB_NOT_CONFIGURED', sources: [], serviceCredits: null, usageUnknown: true }) });
  expect(wrapper.text()).toContain('尚未配置');
  await wrapper.setProps({ text: 'web_read [call]\n{"code":"INTERRUPTED"}' });
  expect(wrapper.text()).toContain('资料调用已中断'); expect(wrapper.text()).toContain('可能已消耗'); wrapper.unmount();
});
it('discards delayed clipboard success after round-trip pagination and shows provider failure evidence', async () => {
  let finish: () => void = () => {};
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn(() => new Promise<void>(resolve => { finish = resolve; })) } });
  const wrapper = mount(CopilotWebResult, { props: { text: body(), scope: 'task/message' } });
  const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)!;
  await button('复制当前资料片段').trigger('click');
  await button('继续查看保留资料').trigger('click'); await button('上一段资料').trigger('click');
  finish(); await flushPromises(); expect(wrapper.text()).not.toContain('当前保留片段已复制');
  await wrapper.setProps({ text: body({ code: 'WEB_FETCH_FAILED', sources: [], serviceCredits: '0', usageUnknown: false, notice: 'Untrusted provider failure: <script>blocked</script>' }) });
  expect(wrapper.text()).toContain('<script>blocked</script>'); expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.text()).toContain('credits：0'); wrapper.unmount();
});
it('resets old pagination on scope changes and explains clipboard failure', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  const wrapper = mount(CopilotWebResult, { props: { text: body(), scope: 'old' } });
  await wrapper.findAll('button').find(item => item.text() === '继续查看保留资料')!.trigger('click');
  await wrapper.setProps({ scope: 'new' }); expect(wrapper.text()).toContain('当前展示 1–2048');
  await wrapper.findAll('button').find(item => item.text() === '复制当前资料片段')!.trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('复制失败'); expect(wrapper.get('pre').text()).toContain('<script>'); wrapper.unmount();
});
