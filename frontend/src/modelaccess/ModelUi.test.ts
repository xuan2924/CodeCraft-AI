import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ModelForm from './ModelForm.vue';
import ModelCheckView from './ModelCheckView.vue';
import ModelSettingsPage from './ModelSettingsPage.vue';
import DeleteConfiguration from './DeleteConfiguration.vue';
import * as api from './api';
import { session, type Identity } from '../auth/api';
import { ApiError } from '../api/request';
vi.mock('../auth/api', () => ({ session: vi.fn() }));
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), connections: vi.fn(), getConnection: vi.fn(), models: vi.fn(), getModel: vi.fn(),
  saveConnection: vi.fn(), saveModel: vi.fn(), deleteConnection: vi.fn(), deleteModel: vi.fn(), latestCheck: vi.fn(), getCheck: vi.fn(), startCheck: vi.fn() }));
const connection: api.Connection = { id: '10000000-0000-0000-0000-000000000001', name: 'Fixture connection', baseUrl: 'https://example.test/v1',
  protocol: 'OPENAI_CHAT', preset: 'CUSTOM', version: '2', configurationVersion: '1', keyConfigured: true, deleted: false };
const model: api.Model = { id: '10000000-0000-0000-0000-000000000002', connectionId: connection.id, name: 'Fixture model', modelId: 'fixture-model',
  contextWindow: 16000, maxOutputTokens: 4096, thinkingMode: 'OFF', reasoningEffort: null, thinkingBudgetTokens: null, temperature: null,
  version: '2', configurationVersion: '1', windowSource: 'USER_PROVIDED', deleted: false };
const check: api.Check = { id: '10000000-0000-0000-0000-000000000003', modelConfigurationId: model.id, connectionConfigurationVersion: '1', modelConfigurationVersion: '1', status: 'QUEUED', startedAt: '2026-01-01T00:00:00Z' };
const item: api.CheckItem = { status: 'PENDING', valid: false, reused: false, completedAt: null, validUntil: null, errorCode: null };
const identity: Identity = { id: 'owner-a', username: 'Account A', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
const wrappers: ReturnType<typeof mount>[] = [];
const clients: QueryClient[] = [];
function client() { const value = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); clients.push(value); return value; }
function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((element) => element.text() === text); if (!found) throw new Error(`Missing button ${text}`); return found;
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] }); vi.setSystemTime('2026-01-01T00:00:00Z');
  vi.resetAllMocks();
  vi.mocked(session).mockResolvedValue(identity);
  vi.mocked(api.connections).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.models).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(api.getConnection).mockResolvedValue(connection);
  vi.mocked(api.getModel).mockResolvedValue(model);
  vi.mocked(api.saveModel).mockResolvedValue(model);
  vi.mocked(api.latestCheck).mockResolvedValue(null);
  vi.mocked(api.startCheck).mockResolvedValue(check);
  vi.mocked(api.getCheck).mockResolvedValue({ check, reason: null, currentConfiguration: true, basic: item, tool: item });
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); clients.splice(0).forEach((queryClient) => queryClient.clear()); vi.useRealTimers(); });

it('validates model capacity, preserves explicit zero temperature, and clears disabled thinking parameters in the frozen payload', async () => {
  const wrapper = mount(ModelForm, { props: { owner: 'owner-a', connection, model: null } }); wrappers.push(wrapper);
  await wrapper.get('#model-new-name').setValue('Fixture model'); await wrapper.get('#model-new-id').setValue('fixture-model');
  await wrapper.get('#model-new-window').setValue('4096'); await wrapper.get('form').trigger('submit');
  expect(api.saveModel).not.toHaveBeenCalled(); expect(wrapper.get('[role="alert"]').text()).toContain('小于窗口');
  await wrapper.get('#model-new-window').setValue('16000'); await wrapper.get('#model-new-thinking').setValue('ON');
  await wrapper.get('#model-new-effort').setValue('high'); await wrapper.get('#model-new-thinking').setValue('OFF');
  await wrapper.get('#model-new-temperature').setValue('0'); await wrapper.get('form').trigger('submit'); await flushPromises();
  const args = vi.mocked(api.saveModel).mock.calls[0];
  expect(args?.[2]).toMatchObject({ thinkingMode: 'OFF', reasoningEffort: null, thinkingBudgetTokens: null, temperature: 0, contextWindow: 16000 });
  expect(Object.isFrozen(args?.[2])).toBe(true); expect(args?.[5]).toBe('owner-a'); expect(api.startCheck).not.toHaveBeenCalled();
});
it('never starts a paid check on mount or without consent, and prevents duplicate clicks while admission is pending', async () => {
  let finish: (value: api.Check) => void = () => {};
  vi.mocked(api.startCheck).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const wrapper = mount(ModelCheckView, { props: { owner: 'owner-a', model, connectionVersion: '1' }, global: { plugins: [[VueQueryPlugin, { queryClient: client() }]] } }); wrappers.push(wrapper);
  await flushPromises(); expect(api.startCheck).not.toHaveBeenCalled();
  expect(button(wrapper, '开始检查').attributes('disabled')).toBeDefined();
  await wrapper.get('input[type="checkbox"]').setValue(true); await button(wrapper, '开始检查').trigger('click');
  await button(wrapper, '开始检查').trigger('click'); expect(api.startCheck).toHaveBeenCalledTimes(1);
  expect(vi.mocked(api.startCheck).mock.calls[0]?.[3]).toBe('owner-a');
  finish(check); await flushPromises(); expect(api.getCheck).toHaveBeenCalled();
  expect(wrapper.get<HTMLInputElement>('input[type="checkbox"]').element.checked).toBe(false);
});
it('drops a late check admission and consent after the owner changes', async () => {
  let finish: (value: api.Check) => void = () => {};
  vi.mocked(api.startCheck).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const wrapper = mount(ModelCheckView, { props: { owner: 'owner-a', model, connectionVersion: '1' }, global: { plugins: [[VueQueryPlugin, { queryClient: client() }]] } }); wrappers.push(wrapper);
  await flushPromises(); await wrapper.get('input[type="checkbox"]').setValue(true); await button(wrapper, '开始检查').trigger('click');
  const signal = vi.mocked(api.startCheck).mock.calls[0]?.[2];
  await wrapper.setProps({ owner: 'owner-b' }); finish(check); await flushPromises();
  expect(signal?.aborted).toBe(true); expect(api.getCheck).not.toHaveBeenCalled();
  expect(wrapper.get<HTMLInputElement>('input[type="checkbox"]').element.checked).toBe(false);
});
it('expires independently reused results by their original times without starting new calls', async () => {
  vi.setSystemTime('2026-01-01T00:29:59Z');
  const passed: api.CheckItem = { ...item, status: 'SUCCEEDED', valid: true, reused: true, completedAt: '2026-01-01T00:00:00Z', validUntil: '2026-01-01T00:30:00Z' };
  vi.mocked(api.latestCheck).mockResolvedValue({ check: { ...check, status: 'SUCCEEDED' }, reason: null, currentConfiguration: true, basic: passed, tool: passed });
  const wrapper = mount(ModelCheckView, { props: { owner: 'owner-a', model, connectionVersion: '1' }, global: { plugins: [[VueQueryPlugin, { queryClient: client() }]] } }); wrappers.push(wrapper);
  await flushPromises(); expect(wrapper.text()).toContain('两项结果均可复用');
  await vi.advanceTimersByTimeAsync(1000); await flushPromises();
  expect(wrapper.text()).toContain('历史通过，当前不可复用'); expect(api.startCheck).not.toHaveBeenCalled();
  wrapper.unmount(); expect(vi.getTimerCount()).toBe(0); wrappers.pop();
});
it.each([{ role: 'ADMIN' as const, passwordChangeRequired: false }, { role: 'USER' as const, passwordChangeRequired: true }])('does not fetch personal connections for an ineligible identity', async (fields) => {
  vi.mocked(session).mockResolvedValue({ ...identity, ...fields });
  const wrapper = mount(ModelSettingsPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client() }]], stubs: { RouterLink: true } } }); wrappers.push(wrapper);
  await flushPromises(); expect(api.connections).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('管理员不能配置个人模型');
});
it('clears the old form on server owner mismatch and loads the newly confirmed owner without retaining its Key', async () => {
  const wrapper = mount(ModelSettingsPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client() }]], stubs: { RouterLink: true } } }); wrappers.push(wrapper);
  await flushPromises(); await button(wrapper, '新建连接').trigger('click');
  await wrapper.get('#connection-new-name').setValue('Private old name'); await wrapper.get('#connection-new-url').setValue('https://example.test');
  await wrapper.get('#connection-new-key').setValue('fixture-old-key');
  vi.mocked(session).mockResolvedValue({ ...identity, id: 'owner-b', username: 'Account B' });
  vi.mocked(api.saveConnection).mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED'));
  await wrapper.get('form').trigger('submit'); await flushPromises(); await flushPromises();
  expect(wrapper.text()).toContain('Account B'); expect(wrapper.text()).not.toContain('Private old name');
  expect(vi.mocked(api.connections).mock.lastCall?.[2]).toBe('owner-b');
  await button(wrapper, '新建连接').trigger('click'); expect(wrapper.get<HTMLInputElement>('#connection-new-key').element.value).toBe('');
});
it.each(['FORBIDDEN', 'PASSWORD_CHANGE_REQUIRED'])('hides private settings after a fresh authorization rejection: %s', async (code) => {
  vi.mocked(session).mockResolvedValueOnce(identity).mockResolvedValue({ ...identity, passwordChangeRequired: true });
  vi.mocked(api.connections).mockRejectedValueOnce(new ApiError(403, code));
  const wrapper = mount(ModelSettingsPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client() }]], stubs: { RouterLink: true } } }); wrappers.push(wrapper);
  await flushPromises(); await flushPromises();
  expect(api.connections).toHaveBeenCalledTimes(1); expect(wrapper.find('[aria-label="个人连接管理"]').exists()).toBe(false);
  expect(wrapper.text()).toContain('普通用户账号登录');
});
it('navigates real connection/model query boundaries and paginates without saving or probing automatically', async () => {
  vi.mocked(api.connections).mockResolvedValue({ items: [connection], nextCursor: check.id });
  vi.mocked(api.models).mockResolvedValue({ items: [model], nextCursor: null });
  const wrapper = mount(ModelSettingsPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client() }]], stubs: { RouterLink: true } } }); wrappers.push(wrapper);
  await flushPromises(); await wrapper.get('nav[aria-label="连接目录"] li button').trigger('click'); await flushPromises();
  await wrapper.get('.models li button').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('暂无保留的检查记录');
  expect(vi.mocked(api.getModel).mock.lastCall?.[0]).toBe(connection.id);
  expect(vi.mocked(api.getModel).mock.lastCall?.[3]).toBe('owner-a');
  await button(wrapper, '下一页连接').trigger('click'); await flushPromises();
  expect(vi.mocked(api.connections).mock.lastCall?.[0]).toBe(check.id);
  expect(api.saveConnection).not.toHaveBeenCalled(); expect(api.saveModel).not.toHaveBeenCalled(); expect(api.startCheck).not.toHaveBeenCalled();
});
it('requires explicit deletion confirmation and keeps the version shown when confirmation opened', async () => {
  vi.mocked(api.deleteModel).mockResolvedValue({ ...model, deleted: true });
  const wrapper = mount(DeleteConfiguration, { props: { owner: 'owner-a', kind: 'model', entity: model } }); wrappers.push(wrapper);
  await button(wrapper, '删除模型').trigger('click'); expect(api.deleteModel).not.toHaveBeenCalled();
  await wrapper.setProps({ entity: { ...model, version: '3' } }); await button(wrapper, '确认删除').trigger('click'); await flushPromises();
  expect(vi.mocked(api.deleteModel).mock.calls[0]?.slice(0, 2)).toEqual([model.id, '2']);
  expect(vi.mocked(api.deleteModel).mock.calls[0]?.[4]).toBe('owner-a'); expect(wrapper.emitted('deleted')).toHaveLength(1);
});
