import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CopilotApprovalMode from './CopilotApprovalMode.vue';
import * as api from './api';
import { ApiError } from '../api/request';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), approvalMode: vi.fn(), changeApprovalMode: vi.fn() }));
const owner = '10000000-0000-0000-0000-000000000001';
const exerciseId = '10000000-0000-0000-0000-000000000002';
const change: api.ApprovalChange = { exerciseId, id: '10000000-0000-0000-0000-000000000003', actorId: owner,
  previousMode: 'ASK', mode: 'AUTO', version: '1', createdAt: '2026-01-01T00:00:00Z' };
const initial: api.ApprovalModeSnapshot = { exerciseId, mode: 'ASK', version: '0', history: [] };
const automatic: api.ApprovalModeSnapshot = { exerciseId, mode: 'AUTO', version: '1', history: [change] };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function panel(disabled = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(client);
  const view = mount(CopilotApprovalMode, { attachTo: document.body, props: { owner, exerciseId, credential: 'control', disabled },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(view); return view;
}
function button(view: ReturnType<typeof mount>, text: string) {
  const result = view.findAll('button').find((item) => item.text().includes(text));
  if (!result) throw new Error(`Missing button containing ${text}`); return result;
}
async function chooseAuto(view: ReturnType<typeof mount>) {
  await flushPromises();
  await view.get('.mode-pill-trigger').trigger('click');
  await button(view, '自动审批').trigger('click');
}
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.approvalMode).mockResolvedValue(initial); vi.mocked(api.changeApprovalMode).mockResolvedValue(change); });
afterEach(() => { wrappers.splice(0).forEach((view) => view.unmount()); clients.splice(0).forEach((client) => client.clear()); document.body.innerHTML = ''; });

it('loads authoritative default and empty audit without mutating or optimistically switching', async () => {
  const view = panel(); await flushPromises();
  expect(view.get('.mode-pill-trigger').text()).toContain('逐次审批');
  await view.get('.mode-pill-trigger').trigger('click');
  await view.get('.audit-link').trigger('click'); await flushPromises();
  expect(document.body.textContent).toContain('尚无模式变更记录');
  expect(api.changeApprovalMode).not.toHaveBeenCalled();
});

it('does not paint a historical command receipt as current after another tab switched back', async () => {
  let resolve!: (value: api.ApprovalChange) => void;
  vi.mocked(api.changeApprovalMode).mockReturnValue(new Promise((done) => { resolve = done; }));
  const view = panel(); await chooseAuto(view);
  expect(view.get('.mode-pill-trigger').text()).toContain('逐次审批');
  vi.mocked(api.approvalMode).mockResolvedValue({ exerciseId, mode: 'ASK', version: '2', history: [
    { ...change, id: '10000000-0000-0000-0000-000000000004', previousMode: 'AUTO', mode: 'ASK', version: '2' }, change] });
  resolve(change); await flushPromises(); await flushPromises();
  expect(view.get('.mode-pill-trigger').text()).toContain('逐次审批');
  expect(view.get('.mode-pill-trigger').text()).not.toContain('自动审批');
});

it('keeps an unknown command locked and retries only its original key and version despite a new snapshot', async () => {
  vi.mocked(api.changeApprovalMode).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(change);
  const view = panel(); await chooseAuto(view); await flushPromises();
  expect(view.text()).toContain('核对原模式切换');
  await view.get('.mode-pill-trigger').trigger('click');
  expect(button(view, '自动审批').attributes('disabled')).toBeDefined();
  await view.get('.mode-pill-trigger').trigger('click');
  vi.mocked(api.approvalMode).mockResolvedValue(automatic);
  await button(view, '核对原模式切换').trigger('click'); await flushPromises();
  const calls = vi.mocked(api.changeApprovalMode).mock.calls;
  expect(calls).toHaveLength(2); expect(calls[0]?.slice(0, 6)).toEqual(calls[1]?.slice(0, 6));
  expect(calls[0]?.slice(0, 5)).toEqual([owner, exerciseId, 'control', 'AUTO', '0']);
});

it('ignores a late command from a previous account', async () => {
  let resolve!: (value: api.ApprovalChange) => void;
  vi.mocked(api.changeApprovalMode).mockReturnValue(new Promise((done) => { resolve = done; }));
  const view = panel(); await chooseAuto(view);
  await view.setProps({ owner: '20000000-0000-0000-0000-000000000001' }); await flushPromises();
  resolve(change); await flushPromises(); await flushPromises();
  await view.get('.mode-pill-trigger').trigger('click');
  expect(view.text()).toContain('v0'); expect(view.text()).not.toContain('v1');
  expect(vi.mocked(api.approvalMode).mock.calls).toHaveLength(2);
});

it('reports load failure without inventing an effective default and keeps read-only controls disabled', async () => {
  vi.mocked(api.approvalMode).mockRejectedValue(new ApiError(503, 'COPILOT_STORAGE_UNAVAILABLE'));
  const failed = panel(); await flushPromises();
  expect(failed.get('.mode-pill-trigger').text()).toContain('模式异常');
  await failed.get('.mode-pill-trigger').trigger('click');
  expect(failed.find('[role="alert"]').exists()).toBe(true);
  vi.mocked(api.approvalMode).mockResolvedValue(initial);
  const readonly = panel(true); await flushPromises();
  await readonly.get('.mode-pill-trigger').trigger('click');
  expect(button(readonly, '自动审批').attributes('disabled')).toBeDefined();
  await button(readonly, '自动审批').trigger('click');
  expect(api.changeApprovalMode).not.toHaveBeenCalled();
});

it('reports a definitive version conflict without retrying or changing the effective mode', async () => {
  vi.mocked(api.changeApprovalMode).mockRejectedValue(new ApiError(409, 'COPILOT_APPROVAL_MODE_CONFLICT'));
  const view = panel(); await chooseAuto(view); await flushPromises();
  expect(view.text()).toContain('模式已被其他请求更新');
  expect(view.get('.mode-pill-trigger').text()).toContain('逐次审批');
  expect(view.text()).not.toContain('核对原模式切换');
  expect(api.changeApprovalMode).toHaveBeenCalledTimes(1);
});
