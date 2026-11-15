import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotContext from './CopilotContext.vue';
import * as api from './api';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), context: vi.fn(), compact: vi.fn(), cancelCompaction: vi.fn() }));
const exercise = '10000000-0000-0000-0000-000000000001';
const job: api.Compaction = { id: '10000000-0000-0000-0000-000000000002', sequence: '1', exerciseId: exercise, trigger: 'MANUAL', state: 'WAITING',
  reason: null, attempts: 0, stopRequested: false, sourceVersion: null, throughSequence: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const empty: api.Context = { exerciseId: exercise, version: '0', summaryId: null, throughSequence: '0', summary: null, requests: [] };
const wrappers: ReturnType<typeof mount>[] = []; const clients: QueryClient[] = [];
function panel(disabled = false, credential = 'control') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotContext, { props: { owner: 'owner', exerciseId: exercise, credential, disabled }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(wrapper); return wrapper;
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label); if (!found) throw new Error(`Missing button ${label}`); return found;
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.context).mockResolvedValue(empty); vi.mocked(api.compact).mockResolvedValue(job);
  vi.mocked(api.cancelCompaction).mockResolvedValue({ ...job, state: 'CANCELLED', stopRequested: true, reason: 'USER_STOP', completedAt: job.createdAt });
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((client) => client.clear()); });

it('observes without paid work and preserves the original intent across unknown outcomes and control rotation', async () => {
  vi.mocked(api.compact).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(job);
  const wrapper = panel(); await flushPromises();
  expect(api.compact).not.toHaveBeenCalled(); expect(button(wrapper, '手动压缩上下文').attributes('disabled')).toBeDefined();
  await wrapper.get('input[type="checkbox"]').setValue(true); await button(wrapper, '手动压缩上下文').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('压缩受理结果未知');
  await button(wrapper, '重新读取上下文状态').trigger('click'); await flushPromises();
  expect(api.compact).toHaveBeenCalledTimes(1); expect(wrapper.emitted('busy')?.at(-1)).toEqual([true]);
  await wrapper.setProps({ credential: 'rotated-control' });
  await button(wrapper, '核对原压缩请求').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.compact).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 4)).toEqual(calls[1]?.slice(0, 4));
  expect(calls[0]?.[2]).toBe('control');
});

it('allows monotone cancellation without write control and does not accept another compaction', async () => {
  vi.mocked(api.context).mockResolvedValue({ ...empty, requests: [job] });
  const wrapper = panel(true, ''); await flushPromises();
  expect(button(wrapper, '手动压缩上下文').attributes('disabled')).toBeDefined();
  await button(wrapper, '取消压缩').trigger('click'); await flushPromises();
  expect(api.cancelCompaction).toHaveBeenCalledTimes(1); expect(api.compact).not.toHaveBeenCalled();
});

it('drops a late mutation response after the account changes', async () => {
  let complete: (value: api.Compaction) => void = () => {};
  vi.mocked(api.compact).mockImplementation(() => new Promise<api.Compaction>((resolve) => { complete = resolve; }));
  const wrapper = panel(); await flushPromises();
  await wrapper.get('input[type="checkbox"]').setValue(true); await button(wrapper, '手动压缩上下文').trigger('click');
  await wrapper.setProps({ owner: 'another-owner' }); await flushPromises();
  const reads = vi.mocked(api.context).mock.calls.length;
  complete(job); await flushPromises();
  expect(api.context).toHaveBeenCalledTimes(reads);
  expect(wrapper.get<HTMLInputElement>('input[type="checkbox"]').element.checked).toBe(false);
  expect(wrapper.text()).not.toContain('压缩受理结果未知');
});

it('renders summary evidence as plain text and exposes unknown charging without resubmitting', async () => {
  vi.mocked(api.context).mockResolvedValue({ ...empty, version: '1', summaryId: job.id, throughSequence: '10', summary: '<img src=x onerror=attack()>',
    requests: [{ ...job, state: 'INTERRUPTED', reason: 'MODEL_RESULT_UNKNOWN', attempts: 1, completedAt: job.createdAt }] });
  const wrapper = panel(); await flushPromises();
  expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.get('pre').text()).toContain('<img');
  expect(wrapper.text()).toContain('供应商可能已计费'); expect(wrapper.text()).toContain('不自动重发');
  expect(api.compact).not.toHaveBeenCalled();
});
