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

it('does not invent thinking and reports clipboard failure without losing code', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  const wrapper = mount(CopilotMessageBody, { props: { text: '```sh\necho ok\n```' } });
  expect(wrapper.find('details').exists()).toBe(false);
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(wrapper.get('[role="status"]').text()).toContain('复制失败');
  expect(wrapper.get('code').text()).toBe('echo ok');
  wrapper.unmount();
});
