import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ResizablePanels from './ResizablePanels.vue';

describe('ResizablePanels', () => {
  it('provides a named keyboard-operable separator between independently labeled panels', async () => {
    const wrapper = mount(ResizablePanels, { attachTo: document.body,
      props: { firstLabel: '题目', secondLabel: '代码', resizeLabel: '调整题目与代码宽度' },
      slots: { first: '<p>要求</p>', second: '<p>源码</p>' } });
    await flushPromises();
    const handle = wrapper.get('[role="separator"]');
    expect(handle.attributes('aria-label')).toBe('调整题目与代码宽度');
    expect(handle.attributes('tabindex')).toBe('0');
    expect(wrapper.get('[aria-label="题目"]').text()).toBe('要求');
    expect(wrapper.get('[aria-label="代码"]').text()).toBe('源码');
    wrapper.unmount();
    expect(document.querySelector('[role="separator"]')).toBeNull();
  });
});
