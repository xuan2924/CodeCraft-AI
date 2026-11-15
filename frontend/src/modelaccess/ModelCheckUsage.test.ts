import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { expect, it, vi } from 'vitest';
import ModelCheckUsage from './ModelCheckUsage.vue';
import * as api from './usageApi';
vi.mock('./usageApi', () => ({ checkUsage: vi.fn() }));

it('discards late private usage after account/check changes and refreshes only reads', async () => {
  let finish: (value: api.CheckUsage) => void = () => {};
  vi.mocked(api.checkUsage).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValue({ checkId: 'new', totals: [], costs: [] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(ModelCheckUsage, { props: { owner: 'old-owner', checkId: 'old' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  try {
    await flushPromises();
    const signal = vi.mocked(api.checkUsage).mock.calls[0]?.[2];
    await wrapper.setProps({ owner: 'new-owner', checkId: 'new' }); await flushPromises();
    expect(signal?.aborted).toBe(true);
    finish({ checkId: 'old', totals: [{ purpose: 'CHECK', calls: '999', completeCalls: '0', pricedCalls: '0', inputTokens: null, ordinaryInputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, outputTokens: null, totalTokens: null }], costs: [] });
    await flushPromises();
    expect(wrapper.text()).not.toContain('999');
    expect(wrapper.text()).toContain('暂无保留');
    await wrapper.get('button').trigger('click'); await flushPromises();
    expect(api.checkUsage).toHaveBeenCalledTimes(3);
    expect(vi.mocked(api.checkUsage).mock.calls[2]?.slice(0, 2)).toEqual(['new-owner', 'new']);
  } finally { wrapper.unmount(); client.clear(); }
});
