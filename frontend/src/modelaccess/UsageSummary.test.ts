import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import UsageSummary from './UsageSummary.vue';

it('separates known subtotals from missing data without rounding tiny costs to zero', () => {
  const wrapper = mount(UsageSummary, { props: { data: { totals: [{ purpose: 'CHAT', calls: '2', completeCalls: '1', inputTokens: '9007199254740993', ordinaryInputTokens: null,
    cacheReadTokens: '1', cacheWriteTokens: null, outputTokens: '0', totalTokens: null, pricedCalls: '1' }],
  costs: [{ purpose: 'CHAT', currency: 'USD', amount: '0.000000003', pricedCalls: '1' }] } } });
  expect(wrapper.text()).toContain('9007199254740993');
  expect(wrapper.text()).toContain('统计可能不完整');
  expect(wrapper.text()).toContain('1 次费用未知');
  expect(wrapper.text()).toContain('0.000000003 USD');
  expect(wrapper.text()).toContain('账单为准');
  expect(wrapper.text()).toContain('未知');
  wrapper.unmount();
});
