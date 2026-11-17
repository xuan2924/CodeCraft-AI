import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotProposals from './CopilotProposals.vue';
import * as api from './api';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), proposals: vi.fn(), proposalDetail: vi.fn(), proposalStatus: vi.fn(), decide: vi.fn() }));
const proposal: api.Proposal = { id: '10000000-0000-0000-0000-000000000001', taskId: '10000000-0000-0000-0000-000000000002',
  sequence: '1', state: 'PENDING', mode: 'ASK', modeVersion: '0', baseRevision: '1', fileCount: 1, resultRevision: null, changed: null, decisionType: null,
  decisionReason: null, failureCode: null, createdAt: '2026-01-01T00:00:00Z', decidedAt: null, completedAt: null };
const wrappers: ReturnType<typeof mount>[] = []; const clients: QueryClient[] = [];
function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const wrapper = mount(CopilotProposals, { props: { owner: 'owner', taskId: proposal.taskId, credential: 'control', disabled: false },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { CodeDiff: { props: ['path', 'before', 'after'], template: '<div aria-label="Diff test boundary">{{ path }}</div>' } } } });
  wrappers.push(wrapper); return wrapper;
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const value = wrapper.findAll('button').find((item) => item.text() === label); if (!value) throw new Error(`Missing button ${label}`); return value;
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.mocked(api.proposals).mockResolvedValue({ items: [proposal], nextCursor: null });
  vi.mocked(api.proposalStatus).mockResolvedValue(proposal);
  vi.mocked(api.proposalDetail).mockResolvedValue({ proposal, files: [{ path: 'src/main/java/Main.java', kind: 'UPDATE', before: '<script>attack()</script>', after: 'class Main {}' }] });
  vi.mocked(api.decide).mockResolvedValue({ ...proposal, state: 'APPLYING', decisionType: 'APPROVE' });
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((client) => client.clear()); vi.useRealTimers(); });
it('shows complete immutable evidence as text and requires explicit whole-batch consent', async () => {
  const wrapper = panel(); await flushPromises();
  expect(api.decide).not.toHaveBeenCalled(); expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.get('pre').text()).toContain('<script>');
  expect(button(wrapper, '批准整批修改').attributes('disabled')).toBeDefined();
  await wrapper.get('input[type="checkbox"]').setValue(true);
  await button(wrapper, '批准整批修改').trigger('click'); await flushPromises();
  expect(vi.mocked(api.decide).mock.calls[0]?.slice(0, 6)).toEqual(['owner', proposal.taskId, proposal.id, 'control', 'APPROVE', null]);
  expect(wrapper.text()).toContain('保留草稿并应用修改');
});
it('retries an unknown decision with the original key and captured content only', async () => {
  vi.mocked(api.decide).mockRejectedValueOnce(new TypeError('offline'));
  const wrapper = panel(); await flushPromises(); await wrapper.get('textarea').setValue('Keep the public API');
  await button(wrapper, '拒绝并继续').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('审批结果未知'); expect(button(wrapper, '批准整批修改').attributes('disabled')).toBeDefined();
  await button(wrapper, '核对原审批请求').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.decide).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 7)).toEqual(calls[1]?.slice(0, 7));
  expect(calls[0]?.slice(4, 6)).toEqual(['REJECT', 'Keep the public API']);
});
it('cannot approve from a read-only page or after the baseline invalidates', async () => {
  const wrapper = panel(); await flushPromises(); await wrapper.get('input[type="checkbox"]').setValue(true);
  await wrapper.setProps({ disabled: true }); expect(button(wrapper, '批准整批修改').attributes('disabled')).toBeDefined();
  await wrapper.setProps({ disabled: false, credential: '' }); expect(button(wrapper, '批准整批修改').attributes('disabled')).toBeDefined();
  vi.mocked(api.proposalStatus).mockResolvedValue({ ...proposal, state: 'INVALIDATED', failureCode: 'REVISION_CONFLICT' });
  await vi.advanceTimersByTimeAsync(2000); await flushPromises();
  expect(wrapper.text()).toContain('原提案已失效'); expect(api.decide).not.toHaveBeenCalled();
});
it('polls bounded metadata instead of repeatedly downloading full diff contents and disposes reads', async () => {
  const wrapper = panel(); await flushPromises();
  await vi.advanceTimersByTimeAsync(6000); await flushPromises();
  expect(api.proposalDetail).toHaveBeenCalledTimes(1); expect(vi.mocked(api.proposalStatus).mock.calls.length).toBeGreaterThan(1);
  const count = vi.mocked(api.proposalStatus).mock.calls.length;
  wrapper.unmount(); wrappers.pop(); await vi.advanceTimersByTimeAsync(6000); await flushPromises();
  expect(api.proposalStatus).toHaveBeenCalledTimes(count); expect(api.decide).not.toHaveBeenCalled();
});
it('settles an unknown command from the immutable proposal outcome without reposting after the task advances', async () => {
  vi.mocked(api.decide).mockRejectedValueOnce(new TypeError('lost response'));
  const wrapper = panel(); await flushPromises(); await wrapper.get('input[type="checkbox"]').setValue(true);
  await button(wrapper, '批准整批修改').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('审批结果未知');
  vi.mocked(api.proposalStatus).mockResolvedValue({ ...proposal, state: 'APPLYING', decisionType: 'APPROVE' });
  await wrapper.setProps({ disabled: true }); await vi.advanceTimersByTimeAsync(2000); await flushPromises();
  expect(wrapper.text()).not.toContain('审批结果未知'); expect(wrapper.text()).toContain('写入结果待核对');
  expect(api.decide).toHaveBeenCalledTimes(1);
});
it('marks automatic registration, keeps its Diff and failure, and offers no manual decision', async () => {
  const automatic: api.Proposal = { ...proposal, mode: 'AUTO', modeVersion: '3' };
  vi.mocked(api.proposals).mockResolvedValue({ items: [automatic], nextCursor: null });
  vi.mocked(api.proposalStatus).mockResolvedValue(automatic);
  const wrapper = panel(); await flushPromises();
  expect(wrapper.text()).toContain('自动应用待校验');
  expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false);
  expect(wrapper.findAll('button').some((item) => item.text() === '批准整批修改')).toBe(false);
  expect(wrapper.findAll('pre')).toHaveLength(2);
  vi.mocked(api.proposalStatus).mockResolvedValue({ ...automatic, state: 'FAILED', failureCode: 'STORAGE_BUDGET_EXCEEDED' });
  await vi.advanceTimersByTimeAsync(2000); await flushPromises();
  expect(wrapper.text()).toContain('自动应用失败'); expect(wrapper.text()).toContain('单场数据容量不足');
  expect(api.decide).not.toHaveBeenCalled();
});

it('ignores a late approval response after the owner changes', async () => {
  let complete: (value: api.Proposal) => void = () => {};
  vi.mocked(api.decide).mockImplementation(() => new Promise<api.Proposal>((resolve) => { complete = resolve; }));
  const wrapper = panel(); await flushPromises(); await wrapper.get('input[type="checkbox"]').setValue(true);
  await button(wrapper, '批准整批修改').trigger('click'); await wrapper.setProps({ owner: 'another-owner' });
  complete({ ...proposal, state: 'APPLIED', resultRevision: '2', changed: true }); await flushPromises();
  expect(wrapper.emitted('changed')).toBeUndefined(); expect(api.decide).toHaveBeenCalledTimes(1);
});
