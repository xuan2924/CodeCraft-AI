import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import PlatformAgentsPage from './PlatformAgentsPage.vue';
import * as api from './api';
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), list: vi.fn(), get: vi.fn(), latest: vi.fn(), check: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.resetAllMocks(); });
const firstId = '10000000-0000-0000-0000-000000000001';
const secondId = '10000000-0000-0000-0000-000000000002';
function version(id: string): api.Version {
  return { id, role: 'INTERVIEWER', state: 'DRAFT', revision: '1', credentialRevision: '1', credentialAvailable: true, createdAt: '2026-01-01T00:00:00Z', publishedAt: null,
    definition: { instructions: 'Only authorized requirements.', skills: [], policyVersion: 'INTERVIEW_V1', connection: { id, name: 'Platform', baseUrl: 'https://example.invalid', protocol: 'OPENAI_CHAT', preset: 'CUSTOM', version: '1', configurationVersion: '1', keyConfigured: true, deleted: false },
      model: { id, connectionId: id, name: 'Platform', modelId: 'fixture', contextWindow: 32768, maxOutputTokens: 4096, thinkingMode: 'OFF', reasoningEffort: null, thinkingBudgetTokens: null, temperature: null, version: '1', configurationVersion: '1', windowSource: 'ADMIN_CONFIGURED', deleted: false } } };
}
async function render(role: 'USER' | 'ADMIN') {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 'OK', data: { id: 'admin', username: 'user', role, passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' } }))));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: PlatformAgentsPage }, { path: '/account', name: 'account', component: { template: '<div />' } }] });
  await router.push('/');
  const page = mount(PlatformAgentsPage, { global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  return { page, client };
}
it('does not fetch platform configuration for an ordinary user', async () => {
  const { page, client } = await render('USER');
  expect(page.text()).toContain('需要管理员身份');
  expect(api.list).not.toHaveBeenCalled();
  page.unmount(); client.clear();
});
it('does not expose cached administrator configuration before fresh identity confirmation', async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const identity = { id: 'admin', username: 'user', role: 'ADMIN', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
  client.setQueryData(['identity-session'], identity);
  client.setQueryData(['platform-agents', 'admin', undefined], { versions: [version(firstId)], roles: [] });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: PlatformAgentsPage }, { path: '/account', name: 'account', component: { template: '<div />' } }] });
  await router.push('/');
  const page = mount(PlatformAgentsPage, { global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  expect(page.text()).toContain('正在确认权限');
  expect(page.find('form').exists()).toBe(false);
  expect(api.list).not.toHaveBeenCalled();
  finish(new Response(JSON.stringify({ code: 'OK', data: { ...identity, role: 'USER' } })));
  await flushPromises();
  expect(page.text()).toContain('需要管理员身份');
  expect(client.getQueryData(['platform-agents', 'admin', undefined])).toBeUndefined();
  page.unmount(); client.clear();
});
it('keeps late check acceptance attached to its original version rather than the new selection', async () => {
  vi.mocked(api.list).mockResolvedValue({ versions: [version(firstId), version(secondId)], roles: [{ role: 'INTERVIEWER', currentVersion: null, revision: '0' }, { role: 'EVALUATOR', currentVersion: null, revision: '0' }] });
  vi.mocked(api.get).mockImplementation(async (_owner, id) => version(id));
  vi.mocked(api.latest).mockResolvedValue(null);
  let finish!: (value: api.Check) => void;
  vi.mocked(api.check).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  const { page, client } = await render('ADMIN');
  await page.get('input[type="checkbox"]').setValue(true);
  await page.findAll('button').find(button => button.text() === '发起检查')!.trigger('click');
  await flushPromises();
  await page.findAll('button').find(button => button.text().includes(secondId))!.trigger('click');
  await flushPromises();
  finish({ id: firstId, versionId: firstId, credentialRevision: '1', state: 'SUCCEEDED', createdAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:01Z', errorCode: null });
  await flushPromises();
  expect(page.get('[aria-label="平台版本详情"]').text()).toContain(secondId);
  expect(page.get('[aria-label="平台检查"]').text()).not.toContain('检查成功');
  expect(client.getQueryData<api.Check>(['platform-check', 'admin', firstId])?.state).toBe('SUCCEEDED');
  page.unmount(); client.clear();
});
