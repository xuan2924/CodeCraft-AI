import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import CopilotTelemetryCard from './CopilotTelemetryCard.vue';
import * as api from './telemetryApi';

vi.mock('./telemetryApi', () => ({ exerciseTelemetry: vi.fn() }));
afterEach(() => { vi.clearAllMocks(); });

it('renders copilot loop telemetry metrics and distinguishes zero from missing values', async () => {
  vi.mocked(api.exerciseTelemetry).mockResolvedValueOnce({
    totals: {
      taskCount: 2,
      rounds: 5,
      tools: 8,
      modelElapsedMs: 1240,
      toolElapsedMs: null,
      readWindows: 2,
      readSlotsUsed: 6,
      exploreChildren: 3,
      microCompactedRounds: 1,
      microSavedBytes: 801,
      recoveredRevisionConflicts: 1,
      steerInterruptions: 0,
      chatInputTokens: 4200,
      chatOutputTokens: 900,
      chatCacheReadTokens: null,
      chatCacheWriteTokens: null,
      readWindowUtilization: 0.75,
    },
    recentTasks: [
      {
        taskId: 't-1',
        state: 'SUCCEEDED',
        createdAt: '2026-11-18T03:00:00Z',
        rounds: 3,
        tools: 5,
        modelElapsedMs: 800,
        toolElapsedMs: 210,
        microSavedBytes: 801,
      },
    ],
  });

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(CopilotTelemetryCard, {
    props: { owner: 'owner-1', exerciseId: 'ex-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] },
  });
  try {
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('2 / 5');
    expect(text).toContain('8 / 2（槽位 75%）');
    expect(text).toContain('1 / 801 B');
    expect(text).toContain('1 / 0');
    expect(text).toContain('入 4200 · 出 900');
    expect(text).toContain('仅统计 CHAT 轮次');
    expect(text).toContain('SUCCEEDED');
  } finally {
    wrapper.unmount();
    client.clear();
  }
});

it('shows empty state when no copilot tasks exist yet', async () => {
  vi.mocked(api.exerciseTelemetry).mockResolvedValueOnce({
    totals: {
      taskCount: 0,
      rounds: 0,
      tools: 0,
      modelElapsedMs: null,
      toolElapsedMs: null,
      readWindows: 0,
      readSlotsUsed: null,
      exploreChildren: 0,
      microCompactedRounds: 0,
      microSavedBytes: null,
      recoveredRevisionConflicts: 0,
      steerInterruptions: 0,
      chatInputTokens: null,
      chatOutputTokens: null,
      chatCacheReadTokens: null,
      chatCacheWriteTokens: null,
      readWindowUtilization: null,
    },
    recentTasks: [],
  });

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(CopilotTelemetryCard, {
    props: { owner: 'owner-1', exerciseId: 'ex-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('暂无已记录的 Copilot 任务遥测');
  } finally {
    wrapper.unmount();
    client.clear();
  }
});
