import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import Button from './Button.vue';

describe('Button', () => {
  it('defaults to a non-submitting button and prevents another action while busy', async () => {
    const wrapper = mount(Button, { props: { loading: true }, slots: { default: '保存' } });
    expect(wrapper.attributes('type')).toBe('button');
    expect(wrapper.attributes('aria-busy')).toBe('true');
    expect(wrapper.attributes('disabled')).toBeDefined();
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toBeUndefined();
    await wrapper.setProps({ loading: false });
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toHaveLength(1);
    wrapper.unmount();
  });
});
