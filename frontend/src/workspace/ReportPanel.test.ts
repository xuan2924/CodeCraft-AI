import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { beforeEach, expect, it, vi } from 'vitest';
import ReportPanel from './ReportPanel.vue';
import * as api from './reportApi';
import { ApiError } from '../api/request';
vi.mock('./reportApi', () => ({ read: vi.fn(), review: vi.fn(), retry: vi.fn(), detail: vi.fn(), pdf: vi.fn() }));
vi.mock('./api', () => ({ current: vi.fn().mockResolvedValue(null), message: (error: unknown) => error instanceof Error ? error.message : 'error' }));
const waiting: api.Report = { exerciseId: 'exercise', reportId: null, generation: 0, state: 'WAITING_REVIEW', finalRevision: '0', verification: 'UNAVAILABLE', finalTestId: null, reviewSkipped: null, platformVersionId: null, attempts: 0, reason: null, evaluation: null, evidence: [] };
function render() { return mount(ReportPanel, { props: { owner: 'owner', exerciseId: 'exercise' }, global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]], stubs: { WorkspaceHistory: true } } }); }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.read).mockResolvedValue(waiting); });
it('reads facts without dispatching and submits a single immutable skip', async () => {
  vi.mocked(api.review).mockResolvedValue({ ...waiting, state: 'QUEUED', generation: 1 });
  const wrapper = render(); await flushPromises();
  expect(wrapper.text()).toContain('冻结修订 0'); expect(wrapper.text()).toContain('平台故障不视为代码失败');
  expect(api.review).not.toHaveBeenCalled();
  await wrapper.findAll('button').find(button => button.text() === '跳过回顾并评价')!.trigger('click'); await flushPromises();
  expect(api.review).toHaveBeenCalledWith('owner', 'exercise', true, '', expect.any(String), expect.any(AbortSignal));
  expect(wrapper.find('textarea').exists()).toBe(false); wrapper.unmount();
});
it('reconciles an uncertain review with the same key and frozen text', async () => {
  vi.mocked(api.review).mockRejectedValue(new Error('network'));
  const wrapper = render(); await flushPromises(); await wrapper.get('textarea').setValue('实现与验证');
  await wrapper.findAll('button').find(button => button.text() === '提交回顾并评价')!.trigger('click'); await flushPromises();
  expect(wrapper.get('textarea').attributes('disabled')).toBeDefined();
  await wrapper.findAll('button').find(button => button.text() === '核对原请求')!.trigger('click'); await flushPromises();
  expect(vi.mocked(api.review).mock.calls[1]?.slice(0, 5)).toEqual(vi.mocked(api.review).mock.calls[0]?.slice(0, 5)); wrapper.unmount();
});
it('shows zero scores, evidence errors and safely renders untrusted text', async () => {
  vi.mocked(api.read).mockResolvedValue({ ...waiting, reportId: 'report', generation: 2, state: 'SUCCEEDED', evaluation: { verification: 'UNAVAILABLE', total: null, conclusion: '<script>not executable</script>', dimensions: [{ key: 'QUALITY', score: 0, feedback: '有证据的零分', evidenceIds: ['file'] }] }, evidence: [{ id: 'file', kind: 'FILE', source: 'Main.java', summary: '冻结源码', truncated: true }] });
  vi.mocked(api.detail).mockRejectedValue(new ApiError(410, 'EXERCISE_DELETED'));
  const wrapper = render(); await flushPromises();
  expect(wrapper.text()).toContain('0 / 25'); expect(wrapper.text()).toContain('<script>not executable</script>'); expect(wrapper.find('script').exists()).toBe(false);
  await wrapper.get('.evidence-button').trigger('click'); await flushPromises();
  expect(api.detail).toHaveBeenCalledWith('owner', 'exercise', 2, 'file', expect.any(AbortSignal)); expect(wrapper.text()).toContain('EXERCISE_DELETED'); wrapper.unmount();
});
it('does not retry an unknown model call until explicit user action', async () => {
  vi.mocked(api.read).mockResolvedValue({ ...waiting, state: 'UNKNOWN', generation: 3, reason: 'EVALUATION_RESULT_UNKNOWN' });
  vi.mocked(api.retry).mockResolvedValue({ ...waiting, state: 'QUEUED', generation: 4 });
  const wrapper = render(); await flushPromises(); expect(api.retry).not.toHaveBeenCalled();
  await wrapper.findAll('button').find(button => button.text() === '明确重试评价')!.trigger('click'); await flushPromises();
  expect(api.retry).toHaveBeenCalledWith('owner', 'exercise', 3, expect.any(String), expect.any(AbortSignal)); wrapper.unmount();
});
it('shows access failure rather than stale private report content', async () => {
  vi.mocked(api.read).mockRejectedValue(new ApiError(403, 'RESOURCE_NOT_FOUND'));
  const wrapper = render(); await flushPromises(); expect(wrapper.text()).toContain('无法读取报告'); expect(wrapper.find('textarea').exists()).toBe(false); wrapper.unmount();
});
it('renders contextTrace with valid zeros, hintsUsed badges, and chronological timeline with evidence link', async () => {
  vi.mocked(api.read).mockResolvedValue({
    ...waiting,
    reportId: 'report',
    generation: 1,
    state: 'SUCCEEDED',
    evidence: [
      { id: 'change:chg-1', kind: 'CHANGE', source: 'chg-1', summary: '代码变更 0 → 1', truncated: false },
    ],
    contextTrace: {
      referenceCount: 2,
      skillLoadCount: 1,
      steerCount: 0,
      btwTaskCount: 1,
      interviewerClarifyCount: 3,
      interviewerDefenseCount: 1,
      hintsUsed: [{ topic: 'discount-rounding', maxLevel: 2 }],
    },
    timeline: [
      { timestamp: '2026-11-17T03:05:00Z', kind: 'WORKSPACE_CHANGED', title: '应用 Copilot 修改提案 (r0 → r1)', detail: 'COPILOT_PATCH · 2 files', revision: '1', sourceId: 'chg-1' },
      { timestamp: '2026-11-17T03:01:00Z', kind: 'INTERVIEWER_ASKED', title: '向面试官澄清需求', detail: 'CLARIFY', revision: '0', sourceId: 'ask-1' },
    ],
  });
  vi.mocked(api.detail).mockResolvedValue({ id: 'change:chg-1', kind: 'CHANGE', source: 'chg-1', summary: '代码变更 0 → 1', truncated: false, content: '{"diff":"ok"}' });
  const wrapper = render(); await flushPromises();

  const trace = wrapper.get('[aria-label="上下文工程与协作轨迹"]');
  expect(trace.text()).toContain('@引用次数');
  expect(trace.text()).toContain('0');
  expect(trace.text()).toContain('discount-rounding · L2');

  const timeline = wrapper.get('[aria-label="演练时间轴回放"]');
  const titles = timeline.findAll('.timeline-title').map(node => node.text());
  expect(titles).toEqual(['向面试官澄清需求', '应用 Copilot 修改提案 (r0 → r1)']);
  expect(timeline.text()).toContain('r1');

  await timeline.findAll('button').find(button => button.text() === '查看关联证据')!.trigger('click');
  await flushPromises();
  expect(api.detail).toHaveBeenCalledWith('owner', 'exercise', 1, 'change:chg-1', expect.any(AbortSignal));
  wrapper.unmount();
});
