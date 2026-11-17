import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SegmentedTabs from './SegmentedTabs.vue';

describe('SegmentedTabs', () => {
  it('renders accessible tabs and updates the active model value', async () => {
    const wrapper = mount(SegmentedTabs, {
      props: {
        label: '左栏切换',
        modelValue: 'files',
        items: [
          { id: 'files', label: '文件' },
          { id: 'problem', label: '题目', badge: 2 },
        ],
      },
    });
    const tabs = wrapper.findAll('[role="tab"]');
    expect(tabs).toHaveLength(2);
    expect(tabs[0]!.attributes('aria-selected')).toBe('true');
    expect(tabs[1]!.text()).toContain('2');
    await tabs[1]!.trigger('click');
    expect(wrapper.emitted('update:modelValue')).toEqual([['problem']]);
    wrapper.unmount();
  });
});
