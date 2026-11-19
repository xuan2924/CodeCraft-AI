import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import CopilotMessageBody from './CopilotMessageBody.vue';

afterEach(() => { vi.unstubAllGlobals(); });

it('separates real thinking and fenced code, copies only code, and never renders supplied HTML', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  const wrapper = mount(CopilotMessageBody, { props: {
    text: 'Result <img src=x onerror=alert(1)>\n\n```java\nreturn 42;\n```', thinking: 'Check the return value',
  } });
  expect(wrapper.get('summary').text()).toBe('思考');
  expect(wrapper.get('details').attributes('open')).toBeUndefined();
  expect(wrapper.get('details').text()).toContain('Check the return value');
  expect(wrapper.find('img').exists()).toBe(false);
  expect(wrapper.get('code').text()).toBe('return 42;');
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(writeText).toHaveBeenCalledWith('return 42;');
  expect(wrapper.text()).toContain('已复制');
  wrapper.unmount();
});

it.each(['> ```sh\n> echo ok\n> ```', '1. Run this:\n\n   ```sh\n   echo ok\n   ```'])('copies nested code without Markdown container markers: %s', async (text) => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  const wrapper = mount(CopilotMessageBody, { props: { text } });
  expect(wrapper.get('code').text()).toBe('echo ok');
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(writeText).toHaveBeenCalledWith('echo ok');
  wrapper.unmount();
});

it('does not invent thinking and reports clipboard failure without losing code', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  const wrapper = mount(CopilotMessageBody, { props: { text: '```sh\necho ok\n```' } });
  expect(wrapper.find('details').exists()).toBe(false);
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(wrapper.get('[role="status"]').text()).toContain('复制失败');
  expect(wrapper.get('code').text()).toBe('echo ok');
  wrapper.unmount();
});

it('renders Markdown headings, emphasis, inline code, tables and safe links while blocking javascript URLs', () => {
  const wrapper = mount(CopilotMessageBody, {
    props: {
      text: [
        '## 诊断结果',
        '',
        '请检查 **SettlementEngine** 中的 `roundHalfUp`，参考 [规范](https://example.com/spec) 和 [危险链接](javascript:alert(1))。',
        '',
        '| 项 | 状态 |',
        '| --- | --- |',
        '| 精度 | 通过 |',
      ].join('\n'),
    },
  });
  expect(wrapper.get('h2').text()).toBe('诊断结果');
  expect(wrapper.get('strong').text()).toBe('SettlementEngine');
  expect(wrapper.get('code.inline-code').text()).toBe('roundHalfUp');
  const links = wrapper.findAll('a');
  expect(links).toHaveLength(1);
  expect(links[0]!.attributes('href')).toBe('https://example.com/spec');
  expect(links[0]!.attributes('rel')).toContain('noopener');
  expect(wrapper.text()).toContain('危险链接');
  expect(wrapper.get('table').text()).toContain('精度');
  wrapper.unmount();
});
