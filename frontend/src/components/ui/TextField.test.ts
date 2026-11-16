import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TextField from './TextField.vue';

describe('TextField', () => {
  it('associates label, help and error with the input and emits edits without altering props', async () => {
    const wrapper = mount(TextField, { props: { label: '账号名', help: '请使用受邀账号', error: '账号格式不正确', modelValue: 'old' } });
    const input = wrapper.get('input');
    expect(wrapper.get('label').attributes('for')).toBe(input.attributes('id'));
    expect(input.attributes('aria-invalid')).toBe('true');
    const descriptions = input.attributes('aria-describedby')!.split(' ').map((id) => wrapper.get(`[id="${id}"]`).text());
    expect(descriptions).toEqual(['请使用受邀账号', '账号格式不正确']);
    await input.setValue('new');
    expect(wrapper.emitted('update:modelValue')).toEqual([['new']]);
    expect(wrapper.props('modelValue')).toBe('old');
    wrapper.unmount();
  });
});
