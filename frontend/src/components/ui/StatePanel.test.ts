import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import StatePanel from './StatePanel.vue';

describe('StatePanel', () => {
  it('announces failure rather than an empty success and exposes a caller-owned recovery action', async () => {
    const wrapper = mount(StatePanel, { props: { kind: 'error', title: '无法加载', description: '检查网络后重试。' }, slots: { default: '<button>重试</button>' } });
    expect(wrapper.attributes('role')).toBe('alert');
    expect(wrapper.text()).toContain('检查网络后重试。');
    expect(wrapper.find('button').exists()).toBe(true);
    await wrapper.setProps({ kind: 'empty', title: '暂无题目', description: '题目发布后会出现在此处。' });
    expect(wrapper.attributes('role')).toBe('status');
    expect(wrapper.text()).not.toContain('无法加载');
    wrapper.unmount();
  });
});
