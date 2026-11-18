import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { expect, it, vi } from 'vitest';
import CopilotUsage from './CopilotUsage.vue';
import * as api from './usageApi';
vi.mock('./usageApi', () => ({ usage: vi.fn() }));
it('shows reported occupancy separately, waits after compaction, and drops late exercise reads', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  const reported: api.CopilotUsage = { exerciseId: 'first', context: { state: 'REPORTED', turnId: 'turn', modelId: 'model', contextWindow: 128000,
    windowSource: 'USER_CONFIGURATION', inputTokens: '12345', dispatchedAt: '2026-11-15T00:00:00Z' }, totals: [], costs: [] };
  vi.mocked(api.usage).mockResolvedValue(reported);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(CopilotUsage, {
    props: { owner: 'owner', exerciseId: 'first' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CopilotTelemetryCard: true } },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('12345 / 128000');
    expect(wrapper.text()).toContain('用户配置');
    vi.mocked(api.usage).mockResolvedValue({ ...reported, context: { ...reported.context, state: 'WAITING_UPDATE', inputTokens: null } });
    await vi.advanceTimersByTimeAsync(5000); await flushPromises();
    expect(wrapper.text()).toContain('已压缩，等待下次请求更新占用');
    expect(wrapper.text()).not.toContain('12345');
    let finish: (value: api.CopilotUsage) => void = () => {};
    vi.mocked(api.usage).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    await vi.advanceTimersByTimeAsync(5000); await flushPromises();
    const signal = vi.mocked(api.usage).mock.calls.at(-1)?.[2];
    vi.mocked(api.usage).mockResolvedValue({ ...reported, exerciseId: 'second', context: { state: 'UNKNOWN', turnId: null, modelId: null, contextWindow: null, windowSource: null, inputTokens: null, dispatchedAt: null } });
    await wrapper.setProps({ exerciseId: 'second', owner: 'new-owner' }); await flushPromises();
    expect(signal?.aborted).toBe(true);
    finish(reported); await flushPromises();
    expect(wrapper.text()).not.toContain('12345');
    expect(wrapper.text()).toContain('占用未知');
  } finally { wrapper.unmount(); client.clear(); vi.useRealTimers(); }
});
